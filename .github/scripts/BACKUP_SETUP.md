# MySQL 자동 백업 설정 가이드

## 개요
AWS EC2 리눅스 환경에서 Docker 컨테이너로 실행 중인 MySQL 전체 DB를 S3로 자동 백업하는 설정 가이드입니다.

## 파일 구성
- `backup.sh` - Main 서버용 백업 스크립트 (S3: `keeping-db-backup-0319/main/`)
- `backup_qr.sh` - QR 서버용 백업 스크립트 (S3: `keeping-db-backup-0319/qr/`)

---

## 설치 절차

### 1. Docker 컨테이너 이름 확인

```bash
docker ps --format "table {{.Names}}\t{{.Image}}"
```

출력 예시에서 MySQL 컨테이너 이름을 확인합니다.

### 2. 스크립트 배포 및 컨테이너명 수정

```bash
# Main 서버
scp backup.sh ec2-user@MAIN_SERVER:/home/ec2-user/backup.sh
ssh ec2-user@MAIN_SERVER "chmod +x /home/ec2-user/backup.sh"

# QR 서버
scp backup_qr.sh ec2-user@QR_SERVER:/home/ec2-user/backup.sh
ssh ec2-user@QR_SERVER "chmod +x /home/ec2-user/backup.sh"
```

각 서버에서 스크립트의 `CONTAINER_NAME` 값을 수정:
```bash
vi /home/ec2-user/backup.sh
# CONTAINER_NAME="YOUR_CONTAINER_NAME" 부분을 실제 컨테이너명으로 변경
```

### 3. DB 비밀번호 설정 (환경변수 파일)

각 서버에서 실행:

```bash
cat > ~/.backup.env << 'EOF'
MYSQL_PASSWORD=YOUR_PASSWORD
EOF

chmod 600 ~/.backup.env
```

**보안 참고**: 이 방식을 사용하면:
- 스크립트에 비밀번호가 하드코딩되지 않음
- 파일 권한(600)으로 다른 사용자 접근 차단
- 버전 관리에서 제외 가능

### 4. crontab 등록

```bash
crontab -e
```

아래 내용 추가 (매일 새벽 3시 실행):
```
0 3 * * * /home/ec2-user/backup.sh
```

---

## 검증 방법

### 수동 테스트
```bash
# 백업 실행
/home/ec2-user/backup.sh

# S3 업로드 확인
aws s3 ls s3://keeping-db-backup-0319/main/   # Main 서버
aws s3 ls s3://keeping-db-backup-0319/qr/     # QR 서버

# 로그 확인
cat /home/ec2-user/backup_error.log

# 로컬 임시 파일 정리 확인
ls /tmp/mysql_backup/
```

### crontab 등록 확인
```bash
crontab -l
```

---

## 백업 옵션 설명

| 옵션 | 설명 |
|------|------|
| `--all-databases` | 전체 데이터베이스 백업 |
| `--single-transaction` | InnoDB 테이블 일관성 보장 (락 없이 백업) |
| `--routines` | 스토어드 프로시저/함수 포함 |
| `--triggers` | 트리거 포함 |

---

## 주의사항

1. **EBS 스냅샷과의 충돌 방지**: 백업 시간(새벽 3시)은 EBS 스냅샷(새벽 2시) 이후로 설정
2. **AWS CLI 설정 필요**: `aws configure`로 S3 접근 권한 설정 필요
3. **~/.backup.env 권한**: 반드시 `chmod 600`으로 설정하여 보안 유지
4. **Docker 컨테이너 실행 확인**: 백업 전 컨테이너가 실행 중인지 확인 필요

## 트러블슈팅

### "Can't connect to local MySQL server through socket" 에러
- MySQL이 Docker 컨테이너로 실행 중일 때 발생
- `docker exec` 방식으로 mysqldump 실행 (현재 스크립트에 적용됨)

### "Access denied" 에러
- `~/.backup.env` 파일의 비밀번호 확인
- 비밀번호에 특수문자가 있으면 따옴표로 감싸기: `MYSQL_PASSWORD='pa$$word'`

### 컨테이너를 찾을 수 없음
```bash
# 실행 중인 컨테이너 확인
docker ps

# 컨테이너명 대신 ID 사용 가능
docker exec abc123def /usr/bin/mysqldump ...
```
