package com.dev.jwtstudy.components;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.stereotype.Component;

import com.dev.jwtstudy.entities.User;
import com.dev.jwtstudy.repositories.UserRepository;

@Component
public class JwtTokenEnhancer implements TokenEnhancer{
	
	@Autowired
	private UserRepository userRepository;

	//Nesta função, será incrementado informações no Token JWT;
	
	@Override
	public OAuth2AccessToken enhance(OAuth2AccessToken accessToken, OAuth2Authentication authentication) {
		User user = userRepository.findByEmail(authentication.getName());
		
		Map<String, Object> additionalInfo = new HashMap<>(); //Cria um HashMap para armazenar as informações adicionais que serão inseridas no token
		additionalInfo.put("userFirstName", user.getFirstName());
		additionalInfo.put("userId", user.getId());
		
		DefaultOAuth2AccessToken token = (DefaultOAuth2AccessToken) accessToken; 
		//Faz um DOWN CASTING do accessToken para DefaultOAuth2AccessToken para poder adicionar informações adicionais
		
		//Esse DownCasting permite com que possa ser utilizado o método setAdditionalInformation
		
		token.setAdditionalInformation(additionalInfo); 
		//Adiciona as informações adicionais ao token
		
		return accessToken;
		
		//OBSERVAÇÃO IMPORTANTE: Se for feito uma melhoria no Token, o mesmo deve ser injetado de volta na classe AuthorizationServerConfig
	}

}
