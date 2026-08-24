echo Minimal setup script running as PID $$
IFACE=""; i=0
while [ -z "$IFACE" ]; do
  CAND=$(ip link show | grep -oE 'p2p-[a-zA-Z0-9_-]+' | head -n1)
  if [ -n "$CAND" ] && [ -r "/sys/class/net/$CAND/ifindex" ]; then
    IFACE=$CAND
  fi
  [ -z "$IFACE" ] && sleep 0.3
  i=$((i+1))
done
echo FOUND_IFACE:$IFACE

ip neigh flush all


# get valid IP Address ranges from /vendor/etc/wifi or fall back to defaults
# default aosp start ip address : "192.168.49.2"
# default aosp end ip address : "192.168.49.254"

# assume defaults
START_ADDR="192.168.49.2"
END_ADDR="192.168.49.254"

RES=$(grep -i -e "ip_addr_start=*" /vendor/etc/wifi/*.conf)
# ONLY UPDATE IF NO ERRORS
if [ $? -eq 0 ]; then {
	IFS='=' read -r _ START_ADDR <<< $RES
}; fi

RES=$(grep -i -e "ip_addr_end=*" /vendor/etc/wifi/*.conf)
if [ $? -eq 0 ]; then {
	IFS='=' read -r _ END_ADDR <<< $RES
}; fi

# GET network part ie 192.168.49
NETWORK_PART=${START_ADDR%.*}

# GET END and START DYNAMIC PARTS
START=${START_ADDR##*.}
END=${END_ADDR##*.}



# ping the entire subnet so that ARP knows peers have connected.
echo Pinging IPs from $NETWORK_PART.$START to $NETWORK_PART.$END ...
while true; do {
seq $START $END | xargs -n 1 -P 10 sh -c 'ping -c 1 -W 1 $1.$2' _ "$NETWORK_PART" > /dev/null
sleep 10
}; done


echo END OF SETUP SCRIPT!
