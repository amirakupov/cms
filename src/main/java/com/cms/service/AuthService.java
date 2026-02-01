package com.cms.service;

import com.cms.config.JwtService;
import com.cms.dto.LoginRequest;
import com.cms.dto.LoginResponse;
import com.cms.entity.User;
import com.cms.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;

    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public LoginResponse login(LoginRequest request){
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(), request.getPassword()
                ));
        User user = userRepository.findByUsername(request.getUsername()).orElseThrow(()-> new UsernameNotFoundException("user not found"));
        String jwtToken = jwtService.generateToken(user);
        return LoginResponse.builder().token(jwtToken).build();

    }
}

