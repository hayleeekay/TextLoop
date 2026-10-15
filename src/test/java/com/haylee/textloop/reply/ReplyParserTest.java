package com.haylee.textloop.reply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReplyParserTest {
    private final ReplyParser parser = new ReplyParser();

    @ParameterizedTest
    @CsvSource({
            "DONE, DONE",
            "done, DONE",
            "dOnE, DONE",
            "CANCEL, CANCEL",
            "cancel, CANCEL",
            "cAnCeL, CANCEL",
            "SNOOZE, SNOOZE",
            "snooze, SNOOZE",
            "sNoOzE, SNOOZE"
    })
    void recognizesBareCommandsRegardlessOfCase(String body, ReplyCommand expected) {
        assertThat(parser.parse(body)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = ReplyCommand.class, names = "UNKNOWN", mode = EnumSource.Mode.EXCLUDE)
    void ignoresSurroundingWhitespace(ReplyCommand command) {
        assertThat(parser.parse(" \t\n" + command.name() + "\r\u2003 ")).isEqualTo(command);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "\t\r\n", "\u2003",
            "SNOOZE 30", "snooze 60", "DONE please",
            "DONE!", "CANCEL.", "SNOOZE?",
            "SCHEDULE", "unknown reply", "DONE CANCEL", "DO NE"
    })
    void returnsUnknownForUnsupportedInput(String body) {
        assertThat(parser.parse(body)).isEqualTo(ReplyCommand.UNKNOWN);
    }
}
