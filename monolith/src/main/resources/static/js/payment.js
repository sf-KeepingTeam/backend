// 토스페이먼츠 SDK 로드
const script = document.createElement('script');
script.src = 'https://js.tosspayments.com/v1/payment-widget';
script.async = true;
document.head.appendChild(script);

let tossPayments;
script.onload = () => {
    tossPayments = TossPayments(TOSS_CLIENT_KEY);
};

// 결제 시작
document.getElementById('paymentForm').addEventListener('submit', async (e) => {
    e.preventDefault();

    const storeId = document.getElementById('storeId').value;
    const amount = parseInt(document.getElementById('amount').value);
    const orderName = document.getElementById('orderName').value || null;

    try {
        // 1단계: 예약 생성
        const reserveResponse = await fetch(
            `${API_BASE_URL}/api/v1/stores/${storeId}/prepayment/reserve?customerId=1`,
            {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },  // JWT 토큰 불필요
                body: JSON.stringify({ amount, orderName })
            }
        );

        if (!reserveResponse.ok) {
            const error = await reserveResponse.json();
            throw new Error(error.message || '예약 실패');
        }

        const reserveData = await reserveResponse.json();
        const { orderId, amount: confirmedAmount, orderName: confirmedOrderName } = reserveData.data;

        console.log('예약 성공:', reserveData);

        // 2단계: 토스 결제창 호출
        tossPayments.requestPayment('카드', {
            amount: confirmedAmount,
            orderId: orderId,
            orderName: confirmedOrderName,
            successUrl: `${window.location.origin}/success.html?storeId=${storeId}`,
            failUrl: `${window.location.origin}/fail.html`,
            customerEmail: 'test@example.com',
            customerName: '테스트사용자'
        });

    } catch (error) {
        console.error('결제 시작 오류:', error);
        alert('결제 시작 실패: ' + error.message);
    }
});
