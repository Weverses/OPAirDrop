# Sourced by settings.sh; serialize the snapshot with one awk process.
json_object() {
    LC_ALL=C awk 'BEGIN {
        printf "{"; first=(ARGV[1]=="--" ? 2 : 1)
        for(i=first;i<ARGC;i+=2) {
            if(i>first)printf ","
            quote(ARGV[i]);printf ":";quote(ARGV[i+1])
        }
        print "}";exit
    }
    function quote(value, j,c) {
        printf "\""
        for(j=1;j<=length(value);j++) {
            c=substr(value,j,1)
            if(c=="\\")printf "\\\\";else if(c=="\"")printf "\\\""
            else if(c=="\n")printf "\\n";else if(c=="\t")printf "\\t";else if(c=="\r")printf "\\r"
            else if(c ~ /[[:cntrl:]]/)printf "?";else printf "%s",c
        }
        printf "\""
    }' -- "$@"
}

app_state() {
    if [ -n "$(pidof "$1" 2>/dev/null || true)" ];then
        echo running
    elif framework timeout 4 pm path --user 0 "$1" 2>/dev/null | grep -q '^package:';then
        echo idle
    else echo not_installed;fi
}
