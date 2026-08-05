package com.bank.txn.service;

import com.bank.txn.domain.AuditLog;
import com.bank.txn.domain.AuditOutcome;
import com.bank.txn.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Writes the audit trail.
 *
 * <p>Every write runs in its own transaction ({@code REQUIRES_NEW}) so that a
 * rejected transfer still leaves a record. A refused attempt is precisely what
 * an auditor needs to see, and rolling it back with the business transaction
 * would erase it.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogs, ObjectMapper objectMapper) {
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor,
                       String action,
                       String entityType,
                       String entityId,
                       AuditOutcome outcome,
                       Map<String, ?> details) {
        try {
            auditLogs.save(new AuditLog(
                    actor, action, entityType, entityId, outcome, toJson(details), currentClientIp()));
        } catch (RuntimeException e) {
            // Auditing must never be the reason a request fails.
            log.error("Failed to write audit log for action {} by {}", action, actor, e);
        }
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{\"serialisationError\":true}";
        }
    }

    private static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return null;
    }
}
