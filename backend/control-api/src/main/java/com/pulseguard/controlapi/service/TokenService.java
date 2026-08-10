package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.domain.User;

public interface TokenService {

    String generateAccessToken(User user);

    long accessTokenValiditySeconds();
}
