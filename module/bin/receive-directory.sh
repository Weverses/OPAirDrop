# Sourced after airdrop-name.sh. Paths cross the root shell only as UTF-8 hex.
RECEIVE_DIRECTORY_KEY=opairshare_receive_directory_hex
valid_receive_directory_hex() {
    [ "${#1}" -le 1024 ] && valid_name_hex "$1" 512 || return 1
    decode_name_hex "$1" | LC_ALL=C awk -F/ 'BEGIN {ok=0}
      NR==1 {
        if($1!="Download"&&$1!="Documents")exit 1
        for(i=1;i<=NF;i++)if($i==""||$i~/^\./||$i~/\.$/||$i~/^ | $/||$i~/[<>:"\\|?*]/||length($i)>200)exit 1
        ok=1
      } END {if(!ok)exit 1}'
}
read_receive_directory_hex() {
    directory_hex=$(/system/bin/settings get global "$RECEIVE_DIRECTORY_KEY" 2>/dev/null) || directory_hex=
    if valid_receive_directory_hex "$directory_hex";then printf '%s' "$directory_hex";fi
}
