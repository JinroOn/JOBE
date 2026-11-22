package com.jinroon.jobe.domain.exploration.sharing;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShareTokenCodecTest {
    private final ShareTokenCodec codec=new ShareTokenCodec("test-key-only-012345678901234567890123456789");
    @Test void tokenIsOpaqueCanonicalAndStableForTheSameNonce(){byte[] nonce=codec.nonce();String token=codec.token(nonce);assertThat(codec.validFormat(token)).isTrue();assertThat(token).hasSize(46);assertThat(codec.token(nonce)).isEqualTo(token);assertThat(codec.matches(nonce,codec.hash(token))).isTrue();}
    @Test void generatedNoncesAndTokensAreDistinct(){Set<String> values=new HashSet<>();for(int i=0;i<100;i++)assertThat(values.add(codec.token(codec.nonce()))).isTrue();}
    @Test void rotationOfServerKeyInvalidatesStoredNonceHashBinding(){byte[] nonce=codec.nonce(),hash=codec.hash(codec.token(nonce));var changed=new ShareTokenCodec("another-test-key-only-0123456789012345678901");assertThat(changed.matches(nonce,hash)).isFalse();}
    @Test void malformedOrNoncanonicalTokensAreRejected(){for(String value:List.of("","S1_short","S1_"+"A".repeat(42)+"B","S2_"+"A".repeat(43),"S1_"+"A".repeat(44),"S1_"+"/".repeat(43)))assertThat(codec.validFormat(value)).as(value).isFalse();assertThat(codec.validFormat(null)).isFalse();}
    @Test void nonceLengthAndHashMismatchFail(){assertThatThrownBy(()->codec.token(new byte[31])).isInstanceOf(IllegalArgumentException.class);assertThat(codec.matches(codec.nonce(),new byte[32])).isFalse();}
}
