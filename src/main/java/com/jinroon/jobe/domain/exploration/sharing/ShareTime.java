package com.jinroon.jobe.domain.exploration.sharing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Sharing timestamps are UTC with MySQL microsecond precision. */
@Component
public class ShareTime { public Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);} }
