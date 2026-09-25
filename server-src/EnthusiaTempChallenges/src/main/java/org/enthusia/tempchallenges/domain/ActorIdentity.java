package org.enthusia.tempchallenges.domain;
import java.util.UUID;
public record ActorIdentity(UUID uuid,String name,Platform platform){ public ActorIdentity{if(uuid==null)throw new IllegalArgumentException("uuid"); if(name==null||name.isBlank())name=uuid.toString(); if(platform==null)platform=Platform.JAVA;} public enum Platform{JAVA,BEDROCK,BEDROCK_LINKED} }
