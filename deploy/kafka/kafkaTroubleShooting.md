# Kafka 기동 트러블슈팅 기록

`deploy/kafka/` 로 KRaft 단일 브로커를 올리면서 실제로 막혔던 것들.
증상 → 오진 → 진짜 원인 → 수정 순으로, **틀렸던 판단도 그대로 남긴다.**

- 대상: `keeping-kafka` (5번째 EC2, t3.medium, Amazon Linux 2023)
- Private IP: **172.31.32.33** / 이미지: `apache/kafka:3.9.0` (KRaft)
- 날짜: 2026-08-24

---

## 1. `advertised.listeners cannot use the nonroutable meta-address 0.0.0.0`

### 증상

```
dependency failed to start: container keeping-kafka is unhealthy
```

```
===> Using provided cluster id z9mCfOoOR3qmqCOFnes3pw ...
Exception in thread "main" java.lang.IllegalArgumentException: requirement failed:
  advertised.listeners cannot use the nonroutable meta-address 0.0.0.0.
  Use a routable IP address.
    at kafka.server.KafkaConfig.validateValues(KafkaConfig.scala:1022)
    at kafka.tools.StorageTool$.execute(StorageTool.scala:79)
    at kafka.docker.KafkaDockerWrapper$.main(KafkaDockerWrapper.scala:48)
```

컨테이너가 뜨자마자 죽고 무한 재시작. `kafka-exporter` / `kafka-ui` 는
`depends_on: service_healthy` 라 같이 못 뜬다.

### 오진 1 — "`.env` 치환이 안 됐다"

에러가 `advertised.listeners` 를 가리키니, `KAFKA_ADVERTISED_HOST` 가 빈 값이라
`PLAINTEXT://:9092` → `0.0.0.0` 이 됐다고 판단했다. **틀렸다.**

```bash
grep KAFKA_ADVERTISED_HOST .env
# KAFKA_ADVERTISED_HOST=172.31.32.33          ← 정상
docker compose config | grep -iE 'ADVERTISED|KAFKA_LISTENERS'
# KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://172.31.32.33:9092   ← 정상
```

### 오진 2 — "옛 컨테이너 로그를 보고 있다"

`docker compose logs` 는 지워지기 전 컨테이너 로그도 보여주므로, `.env` 반영 전에
만들어진 컨테이너의 로그라고 생각했다. **이것도 틀렸다.**
`down -v` 후 새로 띄워도 같은 에러가 났다.

### 결정적 확인 — 컨테이너 안의 env 를 직접 본다

```bash
docker compose run --rm --entrypoint env kafka | grep -iE 'ADVERTISED|CLUSTER_ID|LOG_DIRS'
# KAFKA_LOG_DIRS=/var/lib/kafka/data
# CLUSTER_ID=z9mCfOoOR3qmqCOFnes3pw
# KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://172.31.32.33:9092   ← 컨테이너 안까지 정상
```

**환경변수는 완벽했다. 그런데도 0.0.0.0 이 나온다** → `advertised.listeners` 에
우리가 넣지 않은 항목이 하나 더 있다는 뜻이다.

### 진짜 원인 — CONTROLLER 리스너

Kafka 3.9 는 KRaft 에서 **컨트롤러 리스너도 advertised 목록에 넣어 검증**한다.
그런데 `advertised.listeners` 에 CONTROLLER 항목이 없으면 **`listeners` 값에서 유도**한다.

```
KAFKA_LISTENERS = PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
                                            ^^^^^^^^^^^^^^^^^^^^^^^^
                                            여기서 0.0.0.0 이 유도된다
```

우리가 준 `PLAINTEXT://172.31.32.33:9092` 는 멀쩡히 쓰였고,
**우리가 주지 않은 CONTROLLER 쪽이 `0.0.0.0` 으로 유도되어 검증에 걸렸다.**

공식 예제들이 `CONTROLLER://:9093` 처럼 **호스트를 비워 두는** 이유가 이것이다.
비워 두면 null 로 처리되어 유도 시 0.0.0.0 이 되지 않는다.
"명시적인 게 낫다"고 `0.0.0.0` 을 적은 것이 정확히 함정이었다.

### 수정

```diff
- - KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
+ - KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://localhost:9093
```

`localhost` 로 정한 이유: `controller.quorum.voters=1@localhost:9093` 와 **같은 주소로 맞추기 위해서**다.
단일 브로커라 컨트롤러는 자기 자신하고만 통신하므로 이게 의미상으로도 맞다.
호스트를 비우는(`CONTROLLER://:9093`) 방법도 되지만, voters 와 문자열이 달라져
나중에 읽는 사람이 헷갈린다.

```bash
docker compose down -v
sed -i 's|CONTROLLER://0.0.0.0:9093|CONTROLLER://localhost:9093|' docker-compose.yml
docker compose up -d && sleep 45 && docker compose ps
# keeping-kafka  Up (healthy)
```

### 교훈

| | |
|---|---|
| **에러 메시지가 가리키는 설정 ≠ 원인인 설정** | `advertised.listeners` 에러였지만 범인은 `listeners` 였다 |
| **"내가 준 값"만 보면 안 된다** | Kafka 가 **유도해서 채워 넣는 값**이 있다 |
| **`docker compose config` 로 끝내지 마라** | compose 관점은 정상이었다. `run --rm --entrypoint env` 로 컨테이너 안을 봐야 갈렸다 |
| **`0.0.0.0` 은 bind 주소일 뿐 advertise 주소가 못 된다** | 남에게 알려줄 주소로 "아무 데나"를 쓸 수는 없다 |

---

## 2. `KAFKA_LOG_DIRS` 미지정 — 재생성 시 데이터 유실 (사전 차단)

### 증상

없다. **터지지 않아서 더 위험한 종류.**

### 원인

`apache/kafka` 이미지의 기본 `log.dirs` 는 **`/tmp/kraft-combined-logs`** 다.
그런데 compose 에는 볼륨을 이렇게 잡아 두었다:

```yaml
volumes:
  - kafka_data:/var/lib/kafka/data
```

`KAFKA_LOG_DIRS` 를 지정하지 않으면 Kafka 는 `/tmp` 에 쓰고,
**볼륨은 아무것도 담지 않은 채 마운트만 되어 있다.**
컨테이너를 재생성하는 순간 토픽·메시지가 통째로 사라진다.
"볼륨을 붙였으니 영속된다"고 착각하기 딱 좋다.

### 수정

```yaml
- KAFKA_LOG_DIRS=/var/lib/kafka/data
```

### 확인 방법

```bash
docker exec keeping-kafka ls -la /var/lib/kafka/data
# meta.properties 와 <토픽>-<파티션> 디렉터리가 보여야 한다
docker volume inspect kafka_kafka_data
```

`/var/lib/kafka/data` 가 비어 있고 `/tmp/kraft-combined-logs` 에 내용이 있으면
설정이 안 먹은 것이다.

### 남은 잠재 문제 — 볼륨 마운트 지점 권한

`/var/lib/kafka/data` 는 이미지 안에 **없는 경로**다. Docker 는 없는 마운트 지점을
`root:root 0755` 로 만든다. 이미지는 `appuser`(uid 1000)로 도는데,
경우에 따라 여기에 쓰지 못해 다음 에러가 날 수 있다.

```
Permission denied ... /var/lib/kafka/data
Error while creating log dir
```

**2026-08-24 기준으로는 발생하지 않았다.** 나면 kafka 서비스에 아래를 추가한다.

```yaml
    user: root
```

(단일 브로커 실습 환경이라 root 로 도는 것을 수용한다. 운영이면 이미지에
디렉터리를 미리 만들어 chown 하는 쪽이 맞다.)

---

## 3. Amazon Linux 2023 — compose v2 플러그인이 없다

`dnf install docker` 로는 **docker compose 가 안 깔린다.**

```bash
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user

sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

exit    # 도커 그룹 반영을 위해 재접속
```

확인:

```bash
docker version --format '{{.Server.Version}}' && docker compose version
# 25.0.16
# Docker Compose version v2.29.7
```

---

## 4. IMDSv2 Required — 메타데이터 조회가 401 로 막힌다

인스턴스가 IMDSv2 Required 면 아래는 **빈 값**을 준다.

```bash
curl -s http://169.254.169.254/latest/meta-data/local-ipv4     # ← 401, 빈 값
```

빈 값이 그대로 `KAFKA_ADVERTISED_HOST` 에 들어가면 §1 과 **똑같은 증상**이 나므로
특히 헷갈린다. 토큰을 먼저 받아야 한다.

```bash
TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token \
        -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')
MYIP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
        http://169.254.169.254/latest/meta-data/local-ipv4)
echo "$MYIP"      # 172.31.32.33
```

**넣은 뒤 반드시 눈으로 확인할 것.**

```bash
grep KAFKA_ADVERTISED_HOST .env
```

---

## 5. 기동 확인 체크리스트

```bash
cd ~/keeping/deploy/kafka
docker compose ps
#   keeping-kafka           Up (healthy)
#   keeping-kafka-exporter  Up
#   keeping-kafka-ui        Up

# 토픽 생성/조회 — auto-create 를 꺼놨으므로 명시적으로 만든다
docker exec keeping-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --topic smoke.test --partitions 3 --replication-factor 1
docker exec keeping-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic smoke.test
#   PartitionCount: 3  ReplicationFactor: 1, 각 파티션 Leader: 1

# 실제로 메시지가 흐르는지 (브로커 생존이 아니라 통과를 본다)
docker exec keeping-kafka bash -c \
 'printf "hello-1\nhello-2\nhello-3\n" | /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic smoke.test'
docker exec keeping-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic smoke.test --from-beginning --max-messages 3
#   hello-1 / hello-2 / hello-3

# 관측
curl -s localhost:9308/metrics | grep -E '^kafka_brokers|^kafka_topic_partitions' | head
```

원격(다른 EC2)에서 붙는지 확인 — **`advertised` 가 제대로 박혔는지 최종 검증**:

```bash
# keeping-main 또는 keeping-payment 에서
nc -zv 172.31.32.33 9092
```

---

## 6. 진단할 때 쓴 명령 모음

| 목적 | 명령 |
|---|---|
| compose 가 만드는 최종 설정 | `docker compose config \| grep -iE 'ADVERTISED\|LISTENERS'` |
| **컨테이너 안의 실제 env** | `docker compose run --rm --entrypoint env kafka \| grep -i kafka` |
| 죽은 컨테이너 로그 | `docker compose logs kafka --no-log-prefix \| tail -40` |
| 에러 줄만 | `docker compose logs kafka --no-log-prefix \| grep -iE 'exception\|error\|denied' \| tail` |
| 헬스체크 출력 | `docker inspect keeping-kafka --format '{{.State.Health.Status}} / {{range .State.Health.Log}}{{.Output}}{{end}}'` |
| 볼륨까지 초기화 | `docker compose down -v` |

`down -v` 는 포맷이 실패해 **반쯤 만들어진 메타데이터**가 남았을 때 필수다.
안 지우면 다음 기동에서 `Cluster ID doesn't match` 로 증상이 바뀌어 원인 추적이 더 꼬인다.

---

## 7. 검증 결과 (2026-08-24)

| 항목 | 결과 |
|---|---|
| 브로커 기동 | ✅ `keeping-kafka  Up (healthy)` |
| 토픽 생성 | ✅ `smoke.test` PartitionCount 3 / ReplicationFactor 1 / 각 파티션 Leader 1 |
| **메시지 왕복** | ✅ `hello-1/2/3` 프로듀스 → 컨슘 성공. *브로커 생존이 아니라 통과를 확인했다* |
| **`KAFKA_LOG_DIRS` 반영** | ✅ `/var/lib/kafka/data` 에 `.lock` · `__cluster_metadata-0` · `__consumer_offsets-*` 존재 |
| 볼륨 마운트 권한 | ✅ **문제 없었다.** 소유자가 `appuser:appuser` 로 생성됨 → §2 의 `user: root` 패치는 **불필요** |
| exporter → Prometheus | ✅ loadgen `deploy/monitoring/.env` 에 `KAFKA_HOST=172.31.32.33` 추가 후 target **up** |
| **원격 접속 (main)** | ✅ `keeping-main` → `172.31.32.33:9092` 도달 |
| **원격 접속 (payment)** | ✅ `keeping-payment` → `172.31.32.33:9092` 도달 |

### 아직 안 한 것

| | |
|---|---|
| 볼륨 영속성 | `docker compose down` → `up` 후 토픽이 남는지 미확인 (`down -v` 는 지운다) |
| 앱 연동 | main / payment 에 `KAFKA_BOOTSTRAP_SERVERS` 미주입. 코드 설계 확정 후 |
| 실제 알림 토픽 | `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` 라 명시 생성 필요. 이름 확정 후 |

### 수용한 리스크 — 문서에 반드시 남길 것

| | |
|---|---|
| **replication.factor = 1** | 브로커 1대라 복제본이 없다. **브로커나 디스크가 죽으면 미소비 메시지는 복구 경로 없이 유실된다.** 포트폴리오 범위에서 수용한 제약이지 운영에서 괜찮다는 뜻이 아니다 |
| **kafka-ui 인증 없음** | 보안그룹에서 **내 IP 만** 8090 을 연다. 절대 `0.0.0.0/0` 금지 |
| **PLAINTEXT** | 암호화·인증 없음. VPC 내부 + 보안그룹으로만 막고 있다 |

---

## 8. 토픽 이름 규칙 — 마침표/밑줄 섞지 말 것

`smoke.test` 를 만들 때 나온 경고:

```
WARNING: Due to limitations in metric names, topics with a period ('.') or
underscore ('_') could collide. To avoid issues it is best to use either,
but not both.
```

Kafka 는 지표명을 만들 때 `.` 과 `_` 를 같은 문자로 취급한다.
`payment.approved` 와 `payment_approved` 가 **같은 지표로 뭉개진다.**

→ 실제 알림 토픽은 **`.` 만 사용**한다. 예: `keeping.payment.approved.v1`
"측정하려면 지표가 있어야 한다"는 이 프로젝트의 원칙(result.md §3-9)과 직결된다.
