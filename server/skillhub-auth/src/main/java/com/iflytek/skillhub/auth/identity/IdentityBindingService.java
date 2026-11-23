package com.iflytek.skillhub.auth.identity;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.oauth.AccountDisabledException;
import com.iflytek.skillhub.auth.oauth.AccountMergedException;
import com.iflytek.skillhub.auth.oauth.AccountPendingException;
import com.iflytek.skillhub.auth.oauth.CasdoorRegistration;
import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import com.iflytek.skillhub.auth.oauth.SystemAccountLoginException;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.rbac.PlatformRoleDefaults;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.repository.UserRoleBindingRepository;
import com.iflytek.skillhub.auth.settings.InitialExternalRoleGrantService;
import com.iflytek.skillhub.domain.event.UserActivatedEvent;
import com.iflytek.skillhub.domain.namespace.GlobalNamespaceMembershipService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves external OAuth identities to platform users, creating or updating
 * bindings and user records as needed.
 */
@Service
public class IdentityBindingService {

    private final IdentityBindingRepository bindingRepo;
    private final UserAccountRepository userRepo;
    private final UserRoleBindingRepository roleBindingRepo;
    private final GlobalNamespaceMembershipService globalNamespaceMembershipService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactions;
    private final CasdoorRegistration casdoorRegistration;
    private final InitialExternalRoleGrantService initialRoleGrants;

    @Autowired
    public IdentityBindingService(IdentityBindingRepository bindingRepo,
                                  UserAccountRepository userRepo,
                                  UserRoleBindingRepository roleBindingRepo,
                                  GlobalNamespaceMembershipService globalNamespaceMembershipService,
                                  ApplicationEventPublisher eventPublisher,
                                  PlatformTransactionManager transactionManager,
                                  InitialExternalRoleGrantService initialRoleGrants,
                                  CasdoorRegistration casdoorRegistration) {
        this(bindingRepo, userRepo, roleBindingRepo, globalNamespaceMembershipService, eventPublisher,
                initialRoleGrants,
                requiresNewTransactions(transactionManager), casdoorRegistration);
    }

    IdentityBindingService(IdentityBindingRepository bindingRepo,
                           UserAccountRepository userRepo,
                           UserRoleBindingRepository roleBindingRepo,
                           GlobalNamespaceMembershipService globalNamespaceMembershipService,
                           ApplicationEventPublisher eventPublisher,
                           InitialExternalRoleGrantService initialRoleGrants,
                           TransactionOperations transactions,
                           CasdoorRegistration casdoorRegistration) {
        this.bindingRepo = bindingRepo;
        this.userRepo = userRepo;
        this.roleBindingRepo = roleBindingRepo;
        this.globalNamespaceMembershipService = globalNamespaceMembershipService;
        this.eventPublisher = eventPublisher;
        this.transactions = transactions;
        this.casdoorRegistration = casdoorRegistration;
        this.initialRoleGrants = initialRoleGrants;
    }

    public PlatformPrincipal bindOrCreate(OAuthClaims claims, UserStatus initialStatus) {
        try {
            return transactions.execute(status -> bindOrCreateInTransaction(claims, initialStatus));
        } catch (DataIntegrityViolationException conflict) {
            PlatformPrincipal winner = transactions.execute(status -> resolveConcurrentWinner(claims));
            if (winner != null) {
                return winner;
            }
            throw conflict;
        }
    }

    private PlatformPrincipal bindOrCreateInTransaction(OAuthClaims claims, UserStatus initialStatus) {
        IdentityBinding binding = bindingRepo
            .findByProviderCodeAndSubject(claims.provider(), claims.subject())
            .orElse(null);

        UserAccount user;
        if (binding != null) {
            user = userRepo.findLockedFreshById(binding.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for binding"));
            ensureExternalLoginAllowed(user);
            updateReturningProfile(user, claims);
            user = userRepo.save(user);
        } else {
            user = new UserAccount(
                "usr_" + UUID.randomUUID(),
                claims.providerLogin(),
                trustedEmail(claims),
                (String) claims.extra().get("avatar_url")
            );
            user.setStatus(initialStatus);
            updateCasdoorProfile(user, claims);
            user = userRepo.save(user);

            binding = new IdentityBinding(user.getId(), claims.provider(), claims.subject(), claims.providerLogin());
            // Force the unique identity coordinate to be checked before membership or events run.
            bindingRepo.saveAndFlush(binding);
            if (initialStatus == UserStatus.ACTIVE) {
                initialRoleGrants.grantForNewUser(claims, user.getId());
                globalNamespaceMembershipService.ensureMember(user.getId());
                eventPublisher.publishEvent(
                        new UserActivatedEvent(user.getId(), claims.providerLogin(), claims.email()));
            }
        }

        ensureExternalLoginAllowed(user);

        Set<String> roles = roleBindingRepo.findByUserId(user.getId()).stream()
            .map(rb -> rb.getRole().getCode())
            .collect(Collectors.toSet());
        roles = PlatformRoleDefaults.withDefaultUserRole(roles);

        return new PlatformPrincipal(
            user.getId(), user.getDisplayName(), user.getEmail(),
            user.getAvatarUrl(), claims.provider(), roles
        );
    }

    public void createPendingUserIfAbsent(OAuthClaims claims) {
        try {
            transactions.executeWithoutResult(status -> createPendingUserInTransaction(claims));
        } catch (DataIntegrityViolationException conflict) {
            transactions.executeWithoutResult(status -> handleConcurrentPendingWinner(claims, conflict));
            throw conflict;
        }
    }

    private void createPendingUserInTransaction(OAuthClaims claims) {
        IdentityBinding existingBinding = bindingRepo
            .findByProviderCodeAndSubject(claims.provider(), claims.subject())
            .orElse(null);
        if (existingBinding != null) {
            UserAccount existingUser = userRepo.findById(existingBinding.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for binding"));
            ensureExternalLoginAllowed(existingUser);
            throw new AccountPendingException();
        }

        UserAccount user = new UserAccount(
            "usr_" + UUID.randomUUID(),
            claims.providerLogin(),
            trustedEmail(claims),
            (String) claims.extra().get("avatar_url")
        );
        user.setStatus(UserStatus.PENDING);
        user = userRepo.save(user);

        IdentityBinding binding = new IdentityBinding(user.getId(), claims.provider(), claims.subject(), claims.providerLogin());
        bindingRepo.saveAndFlush(binding);
    }

    private PlatformPrincipal resolveConcurrentWinner(OAuthClaims claims) {
        IdentityBinding binding = bindingRepo
                .findByProviderCodeAndSubject(claims.provider(), claims.subject())
                .orElse(null);
        if (binding == null) {
            return null;
        }
        boolean casdoor = casdoorRegistration.matches(claims.provider());
        UserAccount user = (casdoor ? userRepo.findLockedFreshById(binding.getUserId()) : userRepo.findById(binding.getUserId()))
                .orElseThrow(() -> new IllegalStateException("User not found for binding"));
        ensureExternalLoginAllowed(user);
        if (casdoor) {
            updateReturningProfile(user, claims);
            userRepo.save(user);
        }
        Set<String> roles = roleBindingRepo.findByUserId(user.getId()).stream()
                .map(rb -> rb.getRole().getCode())
                .collect(Collectors.toSet());
        roles = PlatformRoleDefaults.withDefaultUserRole(roles);
        return new PlatformPrincipal(
                user.getId(), user.getDisplayName(), user.getEmail(),
                user.getAvatarUrl(), claims.provider(), roles
        );
    }

    private void handleConcurrentPendingWinner(OAuthClaims claims, DataIntegrityViolationException conflict) {
        IdentityBinding binding = bindingRepo
                .findByProviderCodeAndSubject(claims.provider(), claims.subject())
                .orElseThrow(() -> conflict);
        UserAccount user = userRepo.findById(binding.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for binding"));
        ensureExternalLoginAllowed(user);
        throw new AccountPendingException();
    }

    private static TransactionOperations requiresNewTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private void updateReturningProfile(UserAccount user, OAuthClaims claims) {
        if (trustedEmail(claims) != null) user.setEmail(claims.email());
        if (casdoorRegistration.matches(claims.provider())) {
            updateCasdoorProfile(user, claims);
        } else {
            user.setDisplayName(claims.providerLogin());
            if (claims.extra().get("avatar_url") != null) {
                user.setAvatarUrl((String) claims.extra().get("avatar_url"));
            }
        }
    }

    private void updateCasdoorProfile(UserAccount user, OAuthClaims claims) {
        if (!casdoorRegistration.matches(claims.provider())) return;
        var attributes = claims.extra();
        var properties = attributes.get("properties") instanceof Map<?, ?> nested ? nested : Map.of();
        String name = text(attributes.get("displayName"));
        if (name == null) name = text(attributes.get("name"));
        if (name != null) user.setDisplayName(name);
        String avatar = text(attributes.get("avatar"));
        if (avatar == null) avatar = text(attributes.get("avatar_url"));
        if (avatar != null) user.setAvatarUrl(avatar);
        user.updateEmployeeProfile(text(attributes.get("affiliation")), text(properties.get("department")),
                text(properties.get("job_number")), text(properties.get("job_type")), text(attributes.get("phone")));
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private String trustedEmail(OAuthClaims claims) {
        return claims.emailVerified() ? claims.email() : null;
    }

    private void ensureExternalLoginAllowed(UserAccount user) {
        if (user.isSystemAccount()) {
            throw new SystemAccountLoginException();
        }
        if (user.getStatus() == UserStatus.PENDING) {
            throw new AccountPendingException();
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AccountDisabledException();
        }
        if (user.getStatus() == UserStatus.MERGED) {
            throw new AccountMergedException();
        }
    }
}
