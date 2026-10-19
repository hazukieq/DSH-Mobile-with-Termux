#!/system/bin/sh
# DSH Mobile rm wrapper (v1.3.20)
# 共享存储（/sdcard、/storage）上的删除改为移入 /sdcard/dsh-trash，可恢复；
# 其它路径（App 内部、$PREFIX、/data…）交回系统 rm。混合目标按路径分别处理：
# 共享存储的进回收站，其余真删——不会因为混在一起就整体真删。
# 已知不覆盖：绝对路径 /system/bin/rm、busybox rm、toybox rm、find -delete。

TRASH=${DSH_TRASH:-/sdcard/dsh-trash}
REAL_RM=/system/bin/rm

case "$1" in
  -h|--help|-V|--version) exec "$REAL_RM" "$@" ;;
esac

# ---- 解析选项 -------------------------------------------------------------
flags=""
force=0
for a in "$@"; do
  case "$a" in
    --) ;;
    -*)
      case "$a" in
        -*f*) force=1 ;;
      esac
      case "$a" in
        -[rRfvid]*|--force|--recursive|--verbose|--interactive|--dir)
          flags="$flags $a" ;;
        *) exec "$REAL_RM" "$@" ;;   # 不认识的旗标：整条交回真 rm，避免误删
      esac
      ;;
  esac
done

# ---- 分类目标 -------------------------------------------------------------
# 先算总数与共享存储数；没有共享存储目标时原样交给真 rm（含缺失文件报错语义）
shared_count=0
target_count=0
for a in "$@"; do
  case "$a" in -*) continue ;; esac
  target_count=$((target_count + 1))
  real=$(/system/bin/readlink -f -- "$a" 2>/dev/null)
  [ -n "$real" ] || real="$PWD/$a"
  case "$real" in
    /sdcard/*|/storage/*) shared_count=$((shared_count + 1)) ;;
  esac
done

if [ "$shared_count" -eq 0 ]; then
  exec "$REAL_RM" "$@"
fi

# ---- 建回收站目录 ---------------------------------------------------------
stamp=$(/system/bin/date +%Y%m%d-%H%M%S)
dir="$TRASH/$stamp-$$"
if ! /system/bin/mkdir -p "$dir/files" "$TRASH" 2>/dev/null; then
  echo "rm: cannot create trash dir under $TRASH; nothing deleted" >&2
  exit 1
fi

# ---- 逐个处理 -------------------------------------------------------------
status=0
moved=0
for a in "$@"; do
  case "$a" in -*) continue ;; esac
  real=$(/system/bin/readlink -f -- "$a" 2>/dev/null)
  [ -n "$real" ] || real="$PWD/$a"
  case "$real" in
    /sdcard/*|/storage/*)
      if [ ! -e "$a" ]; then
        [ "$force" -eq 0 ] && echo "rm: cannot remove '$a': No such file or directory" >&2 && status=1
        continue
      fi
      # 保留完整原路径，避免同名文件互相覆盖；用用户写的路径形式（/sdcard/...）
      # 登记，restore 用同样的路径关键字才能直接命中（/storage/emulated/0 仍可通过 readlink 判类）
      abs="$a"
      case "$abs" in /*) ;; *) abs="$PWD/$a" ;; esac
      dest="$dir/files/${abs#/}"
      /system/bin/mkdir -p "$(dirname -- "$dest")" 2>/dev/null
      if /system/bin/mv -f -- "$a" "$dest" 2>/dev/null; then
        printf '%s\t%s\t%s\n' "$stamp" "$abs" "$dest" >> "$TRASH/index.tsv"
        moved=$((moved + 1))
      else
        echo "rm: cannot move '$real' into trash; left untouched" >&2
        status=1
      fi
      ;;
    *)
      # 非共享存储：按原旗标真删
      "$REAL_RM" $flags -- "$a" || status=1
      ;;
  esac
done

[ "$moved" -gt 0 ] && echo "  -> moved $moved item(s) to $dir (run: trash-cli list / restore <keyword|index> / empty)" >&2
exit $status
