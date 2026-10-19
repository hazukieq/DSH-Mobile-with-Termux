#!/system/bin/sh
# DSH Mobile rm wrapper (v1.3.18)
# 共享存储（/sdcard、/storage）上的删除改为移入 /sdcard/dsh-trash，可恢复；
# 其它路径（App 内部、$PREFIX、/data…）一律交回系统 rm，保持脚本语义。
# 这是「底层」拦截：凡是通过 PATH 调用 rm 的场景（zsh/bash、脚本、Agent 工具链）
# 都会经过这里。绝对路径 /system/bin/rm、busybox rm、find -delete 不在范围内。

TRASH=/sdcard/dsh-trash

case "$1" in
  -h|--help|-V|--version) exec /system/bin/rm "$@" ;;
esac

# 统计「非选项参数」个数与其中落在共享存储的个数
count=0
shared=0
for a in "$@"; do
  case "$a" in -*) continue ;; esac
  count=$((count + 1))
  real=$(/system/bin/readlink -f -- "$a" 2>/dev/null)
  [ -n "$real" ] || real="$PWD/$a"
  case "$real" in
    /sdcard/*|/storage/*) shared=$((shared + 1)) ;;
  esac
done

# 无目标、或任一目标不在共享存储 → 原样交给系统 rm
if [ "$count" -eq 0 ] || [ "$shared" -ne "$count" ]; then
  exec /system/bin/rm "$@"
fi

stamp=$(/system/bin/date +%Y%m%d-%H%M%S)
dir="$TRASH/$stamp-$$"
/system/bin/mkdir -p "$dir/files" "$TRASH" 2>/dev/null || exec /system/bin/rm "$@"

moved=0
failed=0
for a in "$@"; do
  case "$a" in -*) continue ;; esac
  if [ ! -e "$a" ]; then
    echo "rm: cannot remove '$a': No such file or directory" >&2
    failed=1
    continue
  fi
  real=$(/system/bin/readlink -f -- "$a" 2>/dev/null)
  [ -n "$real" ] || real="$PWD/$a"
  name=$(/system/bin/basename -- "$real")
  if /system/bin/mv -f -- "$real" "$dir/files/$name" 2>/dev/null; then
    printf '%s\t%s\t%s\n' "$stamp" "$real" "$dir/files/$name" >> "$TRASH/index.tsv"
    moved=$((moved + 1))
  else
    echo "rm: cannot move '$real' into trash; left untouched" >&2
    failed=1
  fi
done

[ "$moved" -gt 0 ] && echo "  -> moved $moved item(s) to $dir (trash-list / trash-restore / trash-empty)" >&2
exit $failed
