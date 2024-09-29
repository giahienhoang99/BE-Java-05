package com.engineerpro.securityexample.service;

import java.io.IOException;
import java.util.List;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineerpro.securityexample.dto.AuthenticationRequest;
import com.engineerpro.securityexample.dto.AuthenticationResponse;
import com.engineerpro.securityexample.dto.RegisterRequest;
import com.engineerpro.securityexample.entity.Role;
import com.engineerpro.securityexample.entity.Token;
import com.engineerpro.securityexample.entity.TokenType;
import com.engineerpro.securityexample.entity.User;
import com.engineerpro.securityexample.repository.TokenRepository;
import com.engineerpro.securityexample.repository.UserRepository;
import com.engineerpro.securityexample.utils.ActivationCodeGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
  private final UserRepository repository;
  private final TokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;
  private final RabbitTemplate rabbitTemplate;

  @Transactional
  public AuthenticationResponse register(User user) {
    User savedUser = repository.save(user);
    // String jwtToken = jwtService.generateToken(user);
    // String refreshToken = jwtService.generateRefreshToken(user);
    // saveUserToken(savedUser, jwtToken);
    rabbitTemplate.convertAndSend("email-exchange", "email-routing-key", savedUser.getId());
    return AuthenticationResponse.builder()
        .accessToken("")
        .refreshToken("")
        .build();
  }

  public AuthenticationResponse registerUser(RegisterRequest request) {
    User user = User.builder()
        .firstname(request.getFirstname())
        .lastname(request.getLastname())
        .email(request.getEmail())
        .password(passwordEncoder.encode(request.getPassword()))
        .activateCode(ActivationCodeGenerator.generateActivationCode(6))
        .role(Role.USER)
        .build();
    return register(user);
  }

  public AuthenticationResponse registerAdmin(RegisterRequest request) {
    User user = User.builder()
        .firstname(request.getFirstname())
        .lastname(request.getLastname())
        .email(request.getEmail())
        .password(passwordEncoder.encode(request.getPassword()))
        .role(Role.ADMIN)
        .build();
    return register(user);
  }

  public AuthenticationResponse authenticate(AuthenticationRequest request) {
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(
            request.getEmail(),
            request.getPassword()));
    User user = repository.findByEmail(request.getEmail())
        .orElseThrow();
    String jwtToken = jwtService.generateToken(user);
    String refreshToken = jwtService.generateRefreshToken(user);
    revokeAllUserTokens(user);
    saveUserToken(user, jwtToken);
    return AuthenticationResponse.builder()
        .accessToken(jwtToken)
        .refreshToken(refreshToken)
        .build();
  }

  private void saveUserToken(User user, String jwtToken) {
    Token token = Token.builder()
        .user(user)
        .token(jwtToken)
        .tokenType(TokenType.BEARER)
        .expired(false)
        .revoked(false)
        .build();
    tokenRepository.save(token);
  }

  private void revokeAllUserTokens(User user) {
    List<Token> validUserTokens = tokenRepository.findAllValidTokenByUser(user.getId());
    if (validUserTokens.isEmpty())
      return;
    validUserTokens.forEach(token -> {
      token.setExpired(true);
      token.setRevoked(true);
    });
    tokenRepository.saveAll(validUserTokens);
  }

  public void refreshToken(
      HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    final String refreshToken;
    final String userEmail;
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return;
    }
    refreshToken = authHeader.substring(7);
    userEmail = jwtService.extractUsername(refreshToken);
    if (userEmail != null) {
      User user = this.repository.findByEmail(userEmail)
          .orElseThrow();
      if (jwtService.isTokenValid(refreshToken, user)) {
        String accessToken = jwtService.generateToken(user);
        revokeAllUserTokens(user);
        saveUserToken(user, accessToken);
        AuthenticationResponse authResponse = AuthenticationResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .build();
        new ObjectMapper().writeValue(response.getOutputStream(), authResponse);
      }
    }
  }
}
