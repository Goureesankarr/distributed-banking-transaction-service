package com.bank.txn.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static String username(Authentication authentication) {
        return authentication.getName();
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
