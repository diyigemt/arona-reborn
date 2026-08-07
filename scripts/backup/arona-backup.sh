#!/usr/bin/env bash
# arona 双库每日备份: MongoDB (docker) + MariaDB, gz 压缩, 各自保留最近 KEEP 份按时间滚动.
#
# 用法: arona-backup.sh [配置文件路径]    (默认 /etc/arona-backup.conf)
# 配置见同目录 arona-backup.conf.example; 两个配置文件都含凭据, 必须 chmod 600.
#
# 恢复速查:
#   MongoDB:  docker exec -i <容器> mongorestore --archive --gzip --drop --stopOnError \
#               --uri "<URI>" --nsExclude "admin.*" < mongo-<时间戳>.archive.gz
#             (--nsExclude 有意不恢复 admin 库, 保留现有 root 用户; 完整实例恢复时才去掉)
#   MariaDB:  set -o pipefail; zcat mariadb-<时间戳>.sql.gz | mariadb --defaults-extra-file=<my.cnf>
#
# 安全边界: 备份目录含全量明文数据, 目录本身 chmod 700; Mongo URI 会短暂出现在
# docker exec 的进程参数里, 多用户主机需改用容器内 --config 文件传递密码.
# 一致性边界: standalone mongod 无 oplog, dump 期间有写入时不保证跨集合单一时点; 定时在低写入窗口.
set -euo pipefail
umask 077

CONF="${1:-/etc/arona-backup.conf}"
[ -r "$CONF" ] || { echo "配置文件不可读: $CONF" >&2; exit 1; }
# shellcheck source=/dev/null
. "$CONF"

: "${BACKUP_DIR:?配置缺少 BACKUP_DIR}"
: "${MONGO_CONTAINER:?配置缺少 MONGO_CONTAINER}"
: "${MONGO_URI:?配置缺少 MONGO_URI}"
: "${MARIADB_DEFAULTS_FILE:?配置缺少 MARIADB_DEFAULTS_FILE}"
: "${MARIADB_DB:?配置缺少 MARIADB_DB}"
KEEP="${KEEP:-3}"
# KEEP=0 时 head -n -0 会输出全部行, 把所有备份删光; 非法值也会在转正后炸掉滚动. 必须先验.
[[ "$KEEP" =~ ^[1-9][0-9]*$ ]] || { echo "KEEP 必须是正整数: $KEEP" >&2; exit 1; }

log() { echo "[$(date '+%F %T')] $*"; }

mkdir -p "$BACKUP_DIR/mongodb" "$BACKUP_DIR/mariadb"
chmod 700 "$BACKUP_DIR"

# 防止上一轮尚未结束时 cron 再次拉起
exec 9>"$BACKUP_DIR/.lock"
flock -n 9 || { log "已有备份进程在运行, 跳过"; exit 0; }

# 中途失败遗留的 .part 半成品一律清掉 (启动时清历史残留, 退出时清本轮残留)
cleanup_parts() { find "$BACKUP_DIR" -name '*.part' -delete; }
trap cleanup_parts EXIT
cleanup_parts

STAMP="$(date +%Y%m%d-%H%M%S)"

# 校验通过才转正, 转正后才按文件名时间序滚动删除旧份; 任何失败都不触碰既有备份.
finalize() { # $1=part 路径  $2=滚动目录  $3=滚动文件名模式
  local part="$1" dir="$2" pattern="$3" final="${1%.part}"
  gzip -t "$part" || { echo "gzip 校验失败: $part" >&2; return 1; }
  [ "$(stat -c %s "$part")" -ge 512 ] || { echo "备份文件过小, 视为失败: $part" >&2; return 1; }
  # flock 已排除并发, 但同一秒内顺序重跑仍会撞名; 覆盖已验证的备份视为失败
  [ ! -e "$final" ] || { echo "目标已存在, 拒绝覆盖: $final" >&2; return 1; }
  mv "$part" "$final"
  # 只滚动本脚本命名模式的文件, NUL 分隔免疫异常文件名; 时间戳在文件名里, 字典序即时间序,
  # head -n -K 输出除最新 K 份外的全部旧份
  find "$dir" -maxdepth 1 -type f -name "$pattern" -print0 | sort -z | head -z -n -"$KEEP" | xargs -0 -r rm -f --
}

# --- MongoDB: 全实例 archive, mongodump 自带 gzip, 经 docker exec stdout 落盘宿主机 ---
mongo_part="$BACKUP_DIR/mongodb/mongo-$STAMP.archive.gz.part"
log "开始备份 MongoDB -> ${mongo_part%.part}"
docker exec "$MONGO_CONTAINER" mongodump --quiet --uri "$MONGO_URI" --archive --gzip > "$mongo_part"
finalize "$mongo_part" "$BACKUP_DIR/mongodb" 'mongo-*.archive.gz'
log "MongoDB 备份完成"

# --- MariaDB: single-transaction 一致性快照, 管道 gzip (pipefail 保证 dump 失败即中止) ---
maria_part="$BACKUP_DIR/mariadb/mariadb-$STAMP.sql.gz.part"
log "开始备份 MariaDB($MARIADB_DB) -> ${maria_part%.part}"
mariadb-dump --defaults-extra-file="$MARIADB_DEFAULTS_FILE" \
  --single-transaction --quick --routines --events --triggers \
  --databases "$MARIADB_DB" | gzip > "$maria_part"
# dump 被中途打断时 gzip 流可能仍然完整, 用尾部结束标记兜底防截断
zcat "$maria_part" | tail -n 1 | grep -q "Dump completed" \
  || { echo "MariaDB dump 缺少结束标记, 视为截断: $maria_part" >&2; exit 1; }
finalize "$maria_part" "$BACKUP_DIR/mariadb" 'mariadb-*.sql.gz'
log "MariaDB 备份完成"

log "全部完成"
