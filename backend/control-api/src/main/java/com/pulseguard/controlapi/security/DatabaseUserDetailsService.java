package com.pulseguard.controlapi.security;

import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads the authentication principal for a given email. */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(EmailNormalizer.normalize(email))
                .map(AuthenticatedUser::from)
                // The message is never surfaced to clients; AuthService converts
                // every authentication failure into one generic response.
                .orElseThrow(() -> new UsernameNotFoundException("No user for the supplied email"));
    }
}

