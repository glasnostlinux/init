#!/bin/sh
# shellcheck disable=1090,1091

# Shared code between boot/shutdown.
. /usr/lib/init/rc.lib

log "Welcome to Glasnost!"

log "Mounting pseudo filesystems..."; {
    mnt nosuid,noexec,nodev    proc     proc /proc
    mnt nosuid,noexec,nodev    sysfs    sys  /sys
    mnt mode=0755,nosuid,nodev tmpfs    run  /run
    mnt mode=0755,nosuid       devtmpfs dev  /dev

    mkdir -p /run/user /run/lock /run/log /dev/pts /dev/shm

    mnt mode=0620,gid=5,nosuid,noexec devpts devpts /dev/pts
    mnt mode=1777,nosuid,nodev        tmpfs  shm    /dev/shm

    # udev created these for us, however other device managers
    # don't. This is fine even when udev is in use.
    {
        ln -s /proc/self/fd /dev/fd
        ln -s fd/0          /dev/stdin
        ln -s fd/1          /dev/stdout
        ln -s fd/2          /dev/stderr
    } 2>/dev/null
}

log "Loading rc.conf settings..."; {
    load_conf
}

log "Running boot pre hooks..."; {
    run_hook pre.boot
}

log "Starting device manager..."; {
    case $CONFIG_DEV in
        udevd)
            udevd -d
            udevadm trigger -c add -t subsystems
            udevadm trigger -c add -t devices
            udevadm settle
        ;;

        mdevd)
            mdevd & pid_mdevd=$!
            mdevd-coldplug
        ;;

        mdev)
            mdev -s
            mdev -df & pid_mdev=$!
        ;;
    esac
}

log "Remounting rootfs as read-only..."; {
    mount -o remount,ro / || sos
}

log "Checking filesystems..."; {
    fsck -ATat noopts=_netdev

    # It can't be assumed that success is 0
    # and failure is > 0.
    [ $? -gt 1 ] && sos
}

log "Mounting rootfs as read-write..."; {
    mount -o remount,rw / || sos
}

log "Mounting all local filesystems..."; {
    mount -a || sos
}

log "Enabling swap..."; {
    swapon -a || sos
}

log "Seeding random..."; {
    random_seed load
}

log "Setting up loopback..."; {
    ip link set up dev lo
}

log "Setting hostname..."; {
    read -r hostname < /etc/hostname
    printf %s "${hostname:-Glasnost}" > /proc/sys/kernel/hostname
} 2>/dev/null

log "Loading sysctl settings..."; {
    # This is a portable equivalent to 'sysctl --system'
    # following the exact same semantics.
    for conf in /run/sysctl.d/*.conf \
                /etc/sysctl.d/*.conf \
                /usr/lib/sysctl.d/*.conf \
                /etc/sysctl.conf; do

        [ -f "$conf" ] || continue

        # Skip conf files we have already seen (basename match).
        case $seen in *" ${conf##*/} "*) continue; esac
        seen=" $seen ${conf##*/} "

        sysctl -p "$conf"
    done
}

log "Killing device manager to make way for service..."; {
    case $CONFIG_DEV in
        udevd)
            udevadm control --exit
        ;;

        mdevd)
            kill "$pid_mdevd"
        ;;

        mdev)
            kill "$pid_mdev"
            command -v mdev > /proc/sys/kernel/hotplug
        ;;
    esac
}

log "Running post boot hooks..."; {
    run_hook boot
    run_hook post.boot
}

# Calculate how long the boot process took to
# complete. This entire process is too cheap!
IFS=. read -r boot_time _ < /proc/uptime

log "Boot stage completed in ${boot_time}s..."

log "Replacing rc.boot with service manager..."; {
    case $CONFIG_SERVICE in
        s6)
            case $CONFIG_INIT in
                s6)
                    # If s6 is init s6-svscan is already PID 1 so attempting to
                    # launch it again just results in an error message..
                ;;

                *)
                    run_exec s6-svscan "$CONFIG_SERVICE_DIR"
                ;;
            esac
        ;;

        runit)
            run_exec respawn /usr/bin/runsvdir -P "$CONFIG_SERVICE_DIR" 'log: ...............................................................................................................................................................................................................................................................'
        ;;
    esac
}

