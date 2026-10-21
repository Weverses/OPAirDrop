# Unlock CN GMS v3.8 feature policy (fei-ke), adapted to preserve unrelated XML.
# Split at markup boundaries so attributes may span lines or share a line.
BEGIN { RS = "<"; ORS = ""; single_quote = sprintf("%c", 39) }
function feature_name(tag, rest, quote, end) {
    if (!match(tag, /[[:space:]]name[[:space:]]*=[[:space:]]*/)) return ""
    rest = substr(tag, RSTART + RLENGTH)
    quote = substr(rest, 1, 1)
    if (quote != "\"" && quote != single_quote) return ""
    rest = substr(rest, 2)
    end = index(rest, quote)
    if (!end) return ""
    return substr(rest, 1, end - 1)
}
NR == 1 { printf "%s", $0; next }
{
    end = index($0, ">")
    tag = substr($0, 1, end)
    name = feature_name(tag)
    if (tag ~ /^feature[[:space:]]/ && tag ~ /\/[[:space:]]*>$/ &&
            (name == "cn.google.services" || name == "com.google.android.feature.services_updater")) {
        printf "%s", substr($0, end + 1)
    } else {
        printf "<%s", $0
    }
}
