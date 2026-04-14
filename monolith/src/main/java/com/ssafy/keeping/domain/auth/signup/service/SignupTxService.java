package com.ssafy.keeping.domain.auth.signup.service;

import com.ssafy.keeping.domain.auth.pin.service.PinAuthService;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.signup.dto.OwnerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.dto.RegisterResponse;
import com.ssafy.keeping.domain.auth.signup.dto.CustomerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.dto.SignupResult;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketPayload;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketService;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.service.CustomerService;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.service.OwnerService;
import com.ssafy.keeping.domain.wallet.service.WalletService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class SignupTxService {

    private final SignupTicketService signupTicketService;
    private final CustomerService customerService;
    private final OwnerService ownerService;
    private final WalletService walletService;
    private final PinAuthService pinAuthService;

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
            // 고객 생성
            Customer saved = customerService.registerCustomer(req, payload);

            // 지갑 생성(멱등)
            walletService.createOrGetIndividualWallet(saved);

            // PIN 저장
            pinAuthService.setOrUpdatePin(saved.getCustomerId(), req.pin());

            RegisterResponse dto = RegisterResponse.from(saved);

            invalidateAfterCommit(req.ticket()); // 성공 커밋 후 티켓 폐기
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

            invalidateAfterCommit(req.ticket()); // 성공 커밋 후 티켓 폐기
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

