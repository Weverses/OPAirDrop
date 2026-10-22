# Sourced by settings.sh. Persist only UTF-8 hex in Settings.Global, never shell text.
AIR_NAME_KEY=opairshare_airdrop_name_hex

valid_name_hex() {
    case "$1" in ''|*[!0-9a-fA-F]*) return 1;; esac
    [ "${#1}" -le $((${2:-64} * 8)) ] && [ $((${#1} % 2)) = 0 ] || return 1
    # Validate UTF-8, Unicode scalar values, controls and 64-character limit.
    awk -v data="$1" -v limit="${2:-64}" 'BEGIN {
        data=tolower(data); n=length(data)/2; count=0; visible=0
        for(i=1;i<=n;) {
            b=byte(i++); cp=b; more=0; minimum=0
            if(b>=194&&b<=223){cp=b-192;more=1;minimum=128}
            else if(b>=224&&b<=239){cp=b-224;more=2;minimum=2048}
            else if(b>=240&&b<=244){cp=b-240;more=3;minimum=65536}
            else if(b>=128)exit 1
            if(i+more-1>n)exit 1
            for(j=0;j<more;j++){b=byte(i++);if(b<128||b>191)exit 1;cp=cp*64+b-128}
            if(cp<minimum||cp>1114111||(cp>=55296&&cp<=57343))exit 1
            if(cp<32||(cp>=127&&cp<=159)||cp==8232||cp==8233)exit 1
            if(cp!=32&&cp!=160&&cp!=5760&&!(cp>=8192&&cp<=8202)&&cp!=8239&&cp!=8287&&cp!=12288&&cp!=65279)visible=1
            if(++count>limit)exit 1
        }
        if(!visible)exit 1
    }
    function byte(i, h){h=substr(data,2*i-1,2);return (index("0123456789abcdef",substr(h,1,1))-1)*16+index("0123456789abcdef",substr(h,2,1))-1}' </dev/null
}

decode_name_hex() { printf '%s' "$1" | /system/bin/xxd -r -p; }
read_name_hex() {
    stored_hex=$(/system/bin/settings get global "$AIR_NAME_KEY" 2>/dev/null) || stored_hex=
    if valid_name_hex "$stored_hex"; then printf '%s' "$stored_hex"; fi
}
default_airdrop_name() {
    market_name=$(/system/bin/getprop ro.vendor.oplus.market.enname)
    if [ -z "$market_name" ];then market_name=$(/system/bin/getprop ro.product.model);fi
    printf '%s' "${market_name:-OPAirShare}"
}
