package org.enthusia.tempchallenges.domain;
import java.util.Locale; import java.util.Objects;
public record SignalKey(SignalType type, String value) {
  public SignalKey { Objects.requireNonNull(type); Objects.requireNonNull(value); value=normalize(type,value); if(value.isBlank()) throw new IllegalArgumentException("blank signal"); }
  public static SignalKey parse(String raw){int i=raw.indexOf(':'); if(i<=0||i==raw.length()-1) throw new IllegalArgumentException("Invalid signal: "+raw); return new SignalKey(SignalType.valueOf(raw.substring(0,i).trim().toUpperCase(Locale.ROOT)),raw.substring(i+1).trim());}
  private static String normalize(SignalType type,String value){String v=value.trim(); return type==SignalType.VANILLA_ADVANCEMENT?v.toLowerCase(Locale.ROOT):v.toUpperCase(Locale.ROOT);}
  @Override public String toString(){return type.name()+':'+value;}
}
