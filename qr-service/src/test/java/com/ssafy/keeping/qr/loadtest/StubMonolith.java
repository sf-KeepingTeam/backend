package com.ssafy.keeping.qr.loadtest;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * monolith 역할 스텁 서버 (부하테스트용).
 *
 * <p>qr-service의 ACL 호출 대상(monolith)을 대신한다. 어떤 경로로 오든 200 JSON을 빠르게 응답하므로, 병목이 "서버 처리"가 아니라 "클라이언트
 * 측 연결 처리(= 커넥션 풀 효과)"에 집중되게 한다. HTTP/1.1 keep-alive를 지원하므로, 풀(재사용) 클라이언트는 연결을 유지하고 재사용 안 하는 클라이언트는
 * 매 요청 새 연결을 맺는다.
 *
 * <p>DB/Redis 불필요. 순수 JDK(com.sun.net.httpserver)만 사용.
 *
 * <p>실행: ./gradlew loadStub -PappArgs="8080"
 */
public class StubMonolith {

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

    // ACL DTO들이 역직렬화할 수 있는 공통 필드를 한 번에 담은 응답(store/menu/wallet/payment 공용)
    byte[] body =
        ("{"
                + "\"storeId\":1,\"storeName\":\"stub-store\",\"ownerId\":100,\"taxIdNumber\":\"000-00-00000\","
                + "\"address\":\"stub\",\"active\":true,"
                + "\"menuId\":1,\"menuName\":\"stub-menu\",\"price\":1000,"
                + "\"verified\":true,\"balance\":1000000,"
                + "\"paymentExists\":false,\"status\":\"NONE\""
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
    server.setExecutor(Executors.newFixedThreadPool(200));
    server.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    System.out.println(
        "[StubMonolith] listening on http://localhost:" + port + "  (Ctrl+C to stop)");
  }
}
