package com.bank.txn.service;

import com.bank.txn.domain.AppUser;
import com.bank.txn.domain.AuditOutcome;
import com.bank.txn.error.ValidationException;
import com.bank.txn.repository.AppUserRepository;
import com.bank.txn.security.JwtService;
import com.bank.txn.web.dto.AuthDtos;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthService(AppUserRepository users,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        AppUser user = new AppUser(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                "ROLE_USER");
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ValidationException("That username is already taken");
        }

        audit.record(user.getUsername(), "USER_REGISTERED", "AppUser", user.getUsername(),
                AuditOutcome.SUCCESS, Map.of());

        return token(user);
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            audit.record(request.username(), "LOGIN_FAILED", "AppUser", request.username(),
                    AuditOutcome.FAILURE, Map.of());
            throw new BadCredentialsException("Invalid username or password");
        }

        AppUser user = users.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        audit.record(user.getUsername(), "LOGIN_SUCCEEDED", "AppUser", user.getUsername(),
                AuditOutcome.SUCCESS, Map.of());

        return token(user);
    }

    private AuthDtos.AuthResponse token(AppUser user) {
        return AuthDtos.AuthResponse.bearer(
                jwtService.issue(user.getUsername(), user.roleList()),
                jwtService.getTtlSeconds(),
                user.getUsername(),
                user.roleList());
    }
}
