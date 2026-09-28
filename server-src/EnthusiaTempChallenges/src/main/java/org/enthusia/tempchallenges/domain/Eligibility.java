package org.enthusia.tempchallenges.domain;
public record Eligibility(boolean eligible,String reason){public static Eligibility allow(){return new Eligibility(true,"eligible");} public static Eligibility deny(String reason){return new Eligibility(false,reason==null?"ineligible":reason);} }
