#!/system/bin/sh
# DSH Mobile trash-cli (v1.3.19)
# 统一管理 /sdcard/dsh-trash 回收站（由 $PREFIX/bin/rm 在删除共享存储时写入）。
#   trash-cli list                     列出回收站条目
#   trash-cli restore <关键字|序号>    恢复（关键字匹配原路径/文件名；序号见 list）
#   trash-cli empty                    清空回收站
# 注意：这是给人和 Agent 用的恢复入口；删除仍然用 rm（wrapper 自动进回收站）。

TRASH=${DSH_TRASH:-/sdcard/dsh-trash}
INDEX="$TRASH/index.tsv"

usage() {
  echo "usage: trash-cli list | restore <keyword|index> | empty"
}

case "$1" in
  list|ls)
    if [ ! -f "$INDEX" ]; then echo "trash is empty"; exit 0; fi
    awk -F'\t' '{ printf "%3d  %s  (from %s)\n", NR, $3, $2 }' "$INDEX"
    ;;
  restore)
    if [ ! -f "$INDEX" ]; then echo "trash is empty"; exit 1; fi
    match="$2"
    if [ -z "$match" ]; then usage; exit 1; fi
    case "$match" in
      *[!0-9]*)
        line=$(grep -F -- "$match" "$INDEX" | tail -1)
        # /sdcard 与 /storage/emulated/0 是同一位置的两种写法：互相兜底匹配
        if [ -z "$line" ]; then
          alt=""
          case "$match" in
            /sdcard/*) alt="/storage/emulated/0/${match#/sdcard/}" ;;
            /storage/emulated/0/*) alt="/sdcard/${match#/storage/emulated/0/}" ;;
          esac
          [ -n "$alt" ] && line=$(grep -F -- "$alt" "$INDEX" | tail -1)
        fi
        ;;
      *) line=$(awk -F'\t' -v n="$match" 'NR==n' "$INDEX") ;;
    esac
    if [ -z "$line" ]; then echo "no matching trash entry"; exit 1; fi
    stored=$(printf '%s\n' "$line" | cut -f3)
    orig=$(printf '%s\n' "$line" | cut -f2)
    if [ ! -e "$stored" ]; then echo "trash entry missing: $stored"; exit 1; fi
    /system/bin/mkdir -p "$(dirname -- "$orig")" 2>/dev/null
    if /system/bin/mv -f -- "$stored" "$orig"; then
      # 用 awk 精确删掉该行的 stored 字段：grep -vF 在「只剩最后一条」时返回 1，
      # 旧的 && 链会跳过 mv，索引里留下幽灵行（v1.3.20 修复）。
      /system/bin/awk -F'\t' -v s="$stored" '$3 != s' "$INDEX" > "$INDEX.tmp" 2>/dev/null
      /system/bin/mv -f "$INDEX.tmp" "$INDEX" 2>/dev/null
      echo "restored: $orig"
    else
      echo "restore failed: $orig" >&2
      exit 1
    fi
    ;;
  empty|clean)
    if [ ! -d "$TRASH" ]; then echo "trash is empty"; exit 0; fi
    /system/bin/find "$TRASH" -mindepth 1 -maxdepth 1 ! -name 'index.tsv' -exec /system/bin/rm -rf -- {} + 2>/dev/null
    : > "$INDEX" 2>/dev/null
    echo "trash emptied"
    ;;
  *)
    usage
    exit 1
    ;;
esac
