package com.pulseguard.controlapi.exception;

import lombok.Getter;

/**
 * An application error that maps directly onto an API response.
 *
 * <p>Services throw this rather than returning status codes, so business rules
 * stay expressed in domain terms and the HTTP mapping lives in one place.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ApiErrorCode errorCode;

    public ApiException(ApiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static ApiException emailAlreadyRegistered() {
        return new ApiException(
                ApiErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
    }

    public static ApiException invalidCredentials() {
        // Deliberately identical whether the email is unknown, the password is
        // wrong, or the account is disabled - the client learns nothing extra.
        return new ApiException(ApiErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }

    public static ApiException userNotFound() {
        return new ApiException(ApiErrorCode.USER_NOT_FOUND, "User not found");
    }

    /**
     * Used both when a project genuinely does not exist and when the caller may
     * not see it, so probing IDs reveals nothing about other users' projects.
     */
    public static ApiException projectNotFound() {
        return new ApiException(ApiErrorCode.PROJECT_NOT_FOUND, "Project not found");
    }

    public static ApiException accessDenied() {
        return new ApiException(
                ApiErrorCode.ACCESS_DENIED, "You do not have permission to perform this action");
    }

    public static ApiException projectMemberNotFound() {
        return new ApiException(
                ApiErrorCode.PROJECT_MEMBER_NOT_FOUND, "Project member not found");
    }

    public static ApiException projectMemberAlreadyExists() {
        return new ApiException(
                ApiErrorCode.PROJECT_MEMBER_ALREADY_EXISTS,
                "This user is already a member of the project");
    }

    /**
     * Used both when a monitor genuinely does not exist and when the caller has
     * no access to its project, so monitor ids cannot be probed across projects.
     */
    public static ApiException monitorNotFound() {
        return new ApiException(ApiErrorCode.MONITOR_NOT_FOUND, "Monitor not found");
    }

    /**
     * Used both when an incident genuinely does not exist and when the caller
     * has no access to the project behind it, so incident ids reveal nothing
     * about other people's outages.
     */
    public static ApiException incidentNotFound() {
        return new ApiException(ApiErrorCode.INCIDENT_NOT_FOUND, "Incident not found");
    }

    /** A configuration rule that Bean Validation annotations cannot express. */
    public static ApiException monitorValidation(String message) {
        return new ApiException(ApiErrorCode.MONITOR_VALIDATION_ERROR, message);
    }

    /** A reporting query whose parameters do not make sense together. */
    public static ApiException monitoringQueryInvalid(String message) {
        return new ApiException(ApiErrorCode.MONITORING_QUERY_INVALID, message);
    }

    public static ApiException projectRequiresAdmin() {
        return new ApiException(
                ApiErrorCode.PROJECT_REQUIRES_ADMIN,
                "A project must always have at least one PROJECT_ADMIN");
    }
}
