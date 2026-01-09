package com.dev.jwtstudy.entities;

import java.time.LocalDateTime;
import java.util.Objects;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

@Entity
@Table(name = "confirmation_token")
public class ConfirmationToken {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(nullable = false, unique = true)
	private String token;
	
	@OneToOne
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;
	
	@Column(nullable = false)
	private LocalDateTime expiresAt;
	
	/*
	 * Escalabilidade funcional (mesmo sem crescer muito)
		Hoje: 
		- Confirmação de cadastro

		Amanhã (cenários extremamente comuns):

		- Reenvio de confirmação
		- Troca de e-mail
		- Reset de senha
		- Verificação em duas etapas
	 * */
	
	public ConfirmationToken() {}
	
	public ConfirmationToken(String token, User user, LocalDateTime expiresAt) {
		this.token = token;
		this.user = user;
		this.expiresAt = expiresAt;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(LocalDateTime expiresAt) {
		this.expiresAt = expiresAt;
	}

	@Override
	public int hashCode() {
		return Objects.hash(expiresAt, id, token, user);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConfirmationToken other = (ConfirmationToken) obj;
		return Objects.equals(expiresAt, other.expiresAt) && Objects.equals(id, other.id)
				&& Objects.equals(token, other.token) && Objects.equals(user, other.user);
	}

}
