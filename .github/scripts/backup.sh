#!/bin/bash

# ===========================================
# MySQL 자동 백업 스크립트 (Main 서버용)
# S3 버킷: keeping-db-backup-0319/main/
# Docker 컨테이너 환경용
# ===========================================

# 설정
BACKUP_DIR="/tmp/mysql_backup"
S3_BUCKET="keeping-db-backup-0319"
S3_FOLDER="main"
ERROR_LOG="/home/ec2-user/backup_error.log"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="mysql_backup_${TIMESTAMP}.sql.gz"

# Docker 설정
CONTAINER_NAME="YOUR_CONTAINER_NAME"  # TODO: docker ps로 확인 후 수정

# 환경변수 파일에서 DB 비밀번호 로드
# ~/.backup.env 파일 내용: MYSQL_PASSWORD=your_password
ENV_FILE="/home/ec2-user/.backup.env"
if [ -f "$ENV_FILE" ]; then
    source "$ENV_FILE"
else
    echo "[$(date)] 환경변수 파일 없음: $ENV_FILE" >> "$ERROR_LOG"
    exit 1
fi

# 백업 디렉토리 생성
mkdir -p "$BACKUP_DIR"

# Docker 컨테이너 내 mysqldump 실행
# --all-databases: 전체 DB 백업
# --single-transaction: InnoDB 일관성 보장
# --routines: 스토어드 프로시저 포함
# --triggers: 트리거 포함
docker exec "$CONTAINER_NAME" /usr/bin/mysqldump \
    -uroot \
    -p"$MYSQL_PASSWORD" \
    --all-databases \
    --single-transaction \
    --routines \
    --triggers \
    2>> "$ERROR_LOG" | gzip > "$BACKUP_DIR/$BACKUP_FILE"

# mysqldump 성공 여부 확인
if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo "[$(date)] mysqldump 실패" >> "$ERROR_LOG"
    exit 1
fi

# S3 업로드
aws s3 cp "$BACKUP_DIR/$BACKUP_FILE" "s3://$S3_BUCKET/$S3_FOLDER/$BACKUP_FILE" 2>> "$ERROR_LOG"

# S3 업로드 성공 시 로컬 파일 삭제
if [ $? -eq 0 ]; then
    rm -f "$BACKUP_DIR/$BACKUP_FILE"
    echo "[$(date)] 백업 성공: $BACKUP_FILE" >> "$ERROR_LOG"
else
    echo "[$(date)] S3 업로드 실패: $BACKUP_FILE" >> "$ERROR_LOG"
    exit 1
fi
