# grant ACCESS_FINE_LOCATION to our package
# but ONLY after boot completed is reported
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done
settings put global hidden_api_policy 1
pm grant ziad_mrx.vcd.wfdsinkapp android.permission.ACCESS_FINE_LOCATION
pm grant ziad_mrx.vcd.wfdsinkapp android.permission.NEARBY_WIFI_DEVICES
pm grant ziad_mrx.vcd.wfdsinkapp android.permission.WRITE_SETTINGS

