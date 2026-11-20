package com.iflytek.skillhub.domain.user;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "user_account")
public class UserAccount {
    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 256)
    private String email;

    @Column(name = "email_revision", nullable = false)
    private long emailRevision;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "employee_company", columnDefinition = "text")
    private String company;

    @Column(name = "employee_department", columnDefinition = "text")
    private String department;

    @Column(name = "employee_number", columnDefinition = "text")
    private String employeeNumber;

    @Column(name = "employee_job_title", columnDefinition = "text")
    private String jobTitle;

    @Column(name = "employee_phone", columnDefinition = "text")
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "merged_to_user_id")
    private String mergedToUserId;

    @Column(name = "system_account", nullable = false)
    private boolean systemAccount = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {}

    public UserAccount(String id, String displayName, String email, String avatarUrl) {
        this.id = id;
        this.displayName = displayName;
        this.email = normalizeEmail(email);
        this.avatarUrl = avatarUrl;
        this.status = UserStatus.ACTIVE;
    }

    public static UserAccount systemAccount(String id, String displayName, String email, String avatarUrl) {
        UserAccount user = new UserAccount(id, displayName, email, avatarUrl);
        user.systemAccount = true;
        return user;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now(Clock.systemUTC());
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now(Clock.systemUTC());
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public long getEmailRevision() { return emailRevision; }
    public void setEmail(String email) {
        String normalized = normalizeEmail(email);
        if (!java.util.Objects.equals(normalizeEmail(this.email), normalized)) {
            emailRevision = Math.incrementExact(emailRevision);
        }
        this.email = normalized;
    }
    public static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getCompany() { return company; }
    public String getDepartment() { return department; }
    public String getEmployeeNumber() { return employeeNumber; }
    public String getJobTitle() { return jobTitle; }
    public String getPhone() { return phone; }
    public void updateEmployeeProfile(String company, String department, String employeeNumber,
                                      String jobTitle, String phone) {
        if (company != null) this.company = company;
        if (department != null) this.department = department;
        if (employeeNumber != null) this.employeeNumber = employeeNumber;
        if (jobTitle != null) this.jobTitle = jobTitle;
        if (phone != null) this.phone = phone;
    }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public String getMergedToUserId() { return mergedToUserId; }
    public void setMergedToUserId(String mergedToUserId) { this.mergedToUserId = mergedToUserId; }
    public boolean isSystemAccount() { return systemAccount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return this.status == UserStatus.ACTIVE; }
}
