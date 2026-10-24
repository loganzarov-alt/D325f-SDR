# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
uid=$(id -u) || exit 1
printf 'D325F_UID=%s\n' "$uid"
[ "$uid" = 0 ] || exit 1
candidate() {
  kind=$1; source=$2
  p=$(readlink -f "$source" 2>/dev/null)
  if [ -z "$p" ] || [ ! -b "$p" ]; then
    [ "$kind" != MOUNT ] || printf 'D325F_BAD_MOUNT\t%s\n' "$source"
    return
  fi
  sectors=$(cat "/sys/class/block/${p##*/}/size" 2>/dev/null)
  if [ -z "$sectors" ]; then
    bytes=$(blockdev --getsize64 "$p" 2>/dev/null)
    case "$bytes" in ''|*[!0-9]*) sectors=? ;; *) sectors=$((bytes / 512)) ;; esac
  fi
  printf 'D325F_CAND\t%s\t%s\t%s\n' "$kind" "$p" "$sectors"
}
for table in /proc/mounts /proc/1/mounts; do
  [ -r "$table" ] || continue
  while read -r source mountpoint fs rest; do
    case "$mountpoint" in
      /firmware|/firmware/image|/vendor/firmware|/vendor/firmware_mnt|/system/vendor/firmware)
        [ "$fs" != vfat ] || candidate MOUNT "$source" ;;
    esac
  done < "$table"
done
for source in /dev/block/by-name/modem /dev/block/bootdevice/by-name/modem \
  /dev/block/platform/*/by-name/modem /dev/block/platform/*/*/by-name/modem \
  /dev/block/platform/*/*/*/by-name/modem; do
  [ ! -b "$source" ] || candidate LABEL "$source"
done
for uevent in /sys/class/block/*/uevent; do
  [ -r "$uevent" ] || continue
  partname=; devname=
  while IFS='=' read -r key value; do
    case "$key" in PARTNAME) partname=$value ;; DEVNAME) devname=$value ;; esac
  done < "$uevent"
  [ "$partname" = modem ] || continue
  case "$devname" in ''|*[!a-zA-Z0-9_/-]*) continue ;; esac
  candidate LABEL "/dev/block/$devname"
done
exit 0
