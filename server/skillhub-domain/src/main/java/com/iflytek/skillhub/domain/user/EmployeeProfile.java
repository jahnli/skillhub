package com.iflytek.skillhub.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Employee attributes maintained by the external identity provider. */
@Embeddable
public record EmployeeProfile(
        @Column(name = "employee_company", columnDefinition = "TEXT") String company,
        @Column(name = "employee_department", columnDefinition = "TEXT") String department,
        @Column(name = "employee_number", columnDefinition = "TEXT") String employeeNumber,
        @Column(name = "employee_job_title", columnDefinition = "TEXT") String jobTitle,
        @Column(name = "employee_phone", columnDefinition = "TEXT") String phoneNumber
) {
    EmployeeProfile merge(EmployeeProfile update) {
        return new EmployeeProfile(
                updated(company, update.company),
                updated(department, update.department),
                updated(employeeNumber, update.employeeNumber),
                updated(jobTitle, update.jobTitle),
                updated(phoneNumber, update.phoneNumber));
    }

    private static String updated(String current, String incoming) {
        if (incoming == null) {
            return current;
        }
        return incoming.isBlank() ? null : incoming;
    }
}
