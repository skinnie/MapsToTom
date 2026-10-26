#!/bin/sh

# TTconv menu button: clean restart of the whole GPS chain
# (stops ttconv + gltt, then starts gltt + one ttconv). Logged to gl/log.
echo "`date` up `cut -d' ' -f1 /proc/uptime`s menu: GPS restart" >> /mnt/sdcard/gl/log/ttconv.log
/etc/rc.gltt stop
sleep 1
/etc/rc.gltt start 115200
