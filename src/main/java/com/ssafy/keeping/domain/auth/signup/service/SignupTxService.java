package com.ssafy.keeping.domain.auth.signup.service;

import com.ssafy.keeping.domain.auth.pin.service.PinAuthService;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.signup.dto.OwnerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.dto.RegisterResponse;
import com.ssafy.keeping.domain.auth.signup.dto.CustomerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.dto.SignupResult;
import com.ssafy.keeping.domain.auth.signup.event.CustomerCreatedPayload;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketPayload;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketService;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.service.CustomerService;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.service.OwnerService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.outbox.service.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 회원가입 트랜잭션 서비스
 *
 * 이벤트 기반 분리 (MSA 전환 완료):
 * - Customer 생성 후 CustomerCreated 이벤트 발행
 * - Wallet 생성은 CustomerCreatedEventHandler에서 비동기 처리
 * - PIN 저장은 Auth 도메인 내에서 직접 처리
 *
 * 참고: Wallet 생성이 비동기이므로 회원가입 직후 Wallet 관련 작업 시
 * WalletService.createOrGetIndividualWallet()의 멱등성에 의존
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignupTxService {

    private final SignupTicketService signupTicketService;
    private final CustomerService customerService;
    private final OwnerService ownerService;
    private final PinAuthService pinAuthService;
    private final OutboxPublisher outboxPublisher;

    @Transactional
    public SignupResult signupCustomerTx(CustomerSignupRequest req) {
        SignupTicketPayload payload = validateAndLoadTicket(req.ticket(), UserRole.CUSTOMER);

        // 이미 가입된 고객이면 그대로 DTO로 반환 (중복가입 방지)
        var existing = customerService.findByProviderTypeAndProviderId(payload.providerType(), payload.providerId());
        if (existing.isPresent()) {
            Customer customer = existing.get();
            RegisterResponse dto = RegisterResponse.from(customer);

            invalidateAfterCommit(req.ticket());
            return new SignupResult(customer.getCustomerId(), payload.role(), dto);
        }

        try {
            // 1. 고객 생성 (User 도메인)
            Customer saved = customerService.registerCustomer(req, payload);

            // 2. PIN 저장 (Auth 도메인 내)
            pinAuthService.setOrUpdatePin(saved.getCustomerId(), req.pin());

            // 3. CustomerCreated 이벤트 발행 (Wallet 생성 등 후속 처리)
            // CustomerCreatedEventHandler에서 비동기로 Wallet 생성
            outboxPublisher.publish(
                    "Customer",
                    saved.getCustomerId().toString(),
                    "CustomerCreated",
                    CustomerCreatedPayload.builder()
                            .customerId(saved.getCustomerId())
                            .name(saved.getName())
                            .phone(saved.getPhoneNumber())
                            .build()
            );

            log.info("[회원가입] 완료 - customerId: {}, 이벤트 발행됨", saved.getCustomerId());

            RegisterResponse dto = RegisterResponse.from(saved);

            invalidateAfterCommit(req.ticket());
            return new SignupResult(saved.getCustomerId(), payload.role(), dto);

        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CUSTOMER_DUPLICATE, e);
        }
    }

    @Transactional
    public SignupResult signupOwnerTx(OwnerSignupRequest req) {
        SignupTicketPayload payload = validateAndLoadTicket(req.ticket(), UserRole.OWNER);

        // 이미 가입된 점주면 그대로 DTO로 반환 (중복가입 방지)
        var existing = ownerService.findByProviderTypeAndProviderId(payload.providerType(), payload.providerId());
        if (existing.isPresent()) {
            Owner owner = existing.get();
            RegisterResponse dto = RegisterResponse.from(owner);

            invalidateAfterCommit(req.ticket());
            return new SignupResult(owner.getOwnerId(), payload.role(), dto);
        }

        try {
            // 점주 생성
            Owner saved = ownerService.registerOwner(req, payload);

            RegisterResponse dto = RegisterResponse.from(saved);

            invalidateAfterCommit(req.ticket());
            return new SignupResult(saved.getOwnerId(), payload.role(), dto);

        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.OWNER_DUPLICATE, e);
        }
    }

    private SignupTicketPayload validateAndLoadTicket(String ticket, UserRole expectedRole) {
        SignupTicketPayload payload = signupTicketService.getPayload(ticket);
        if (payload == null) throw new CustomException(ErrorCode.SIGNUP_TICKET_INVALID);
        if (payload.role() != expectedRole) throw new CustomException(ErrorCode.SIGNUP_ROLE_MISMATCH);
        return payload;
    }

    private void invalidateAfterCommit(String ticket) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            signupTicketService.invalidate(ticket);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                signupTicketService.invalidate(ticket);
            }
        });
    }
}
