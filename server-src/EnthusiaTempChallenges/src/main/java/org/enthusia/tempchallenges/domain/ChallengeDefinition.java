package org.enthusia.tempchallenges.domain;
import java.util.Set;
public record ChallengeDefinition(String id,String title,String announcement,String permission,String tag,String advancementNode,int xp,boolean locked,String lockReason,Set<SignalKey> signals){ public ChallengeDefinition{ if(id==null||id.isBlank())throw new IllegalArgumentException("id"); if(permission==null||permission.isBlank())throw new IllegalArgumentException("permission"); signals=Set.copyOf(signals); if(xp<0)throw new IllegalArgumentException("xp"); } }
