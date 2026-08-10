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

    public static ApiException projectRequiresAdmin() {
        return new ApiException(
                ApiErrorCode.PROJECT_REQUIRES_ADMIN,
                "A project must always have at least one PROJECT_ADMIN");
    }
}
