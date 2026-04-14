package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;

public final class WalletFixtures {

    private WalletFixtures() {}

    public static Wallet individualWallet(Customer customer) {
        return Wallet.builder()
                .customer(customer)
                .walletType(WalletType.INDIVIDUAL)
                .build();
    }
}
