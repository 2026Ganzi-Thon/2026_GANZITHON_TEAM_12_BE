package com.comedown.checkin.infrastructure.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.domain.measurement.HrvAnalyzer;
import com.comedown.support.SyntheticPpg;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LocalSignalRecorderTest {

    private static final long USER_ID = 987_654;

    @TempDir
    Path dir;

    private final SyntheticPpg signal = SyntheticPpg.clean(70_000, 30);
    private final RecordCameraMeasurement command = new RecordCameraMeasurement(USER_ID, LocalDate.of(2026, 11, 10),
            Instant.parse("2026-11-11T22:28:50Z"), true, "captureTime", signal.t, signal.r, signal.g);
    private final HrvAnalyzer.Result result = HrvAnalyzer.analyze(signal.t, signal.r, signal.g);

    @Test
    void 기본값_꺼짐이면_파일을_남기지_않는다() throws Exception {
        new LocalSignalRecorder(false, dir.toString(), JsonMapper.builder().build()).record(1, command, result);

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void 켜면_원신호와_결과를_사용자_식별자_없이_남긴다() throws Exception {
        new LocalSignalRecorder(true, dir.toString(), JsonMapper.builder().build()).record(77, command, result);

        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.toList();
        }
        assertThat(files).singleElement()
                .satisfies(p -> assertThat(p.getFileName().toString())
                        .isEqualTo("20261111-222850-m77.hrv-recording.json"));
        String content = Files.readString(files.getFirst());
        assertThat(content).contains("\"measurementId\":77", "\"t\":[", "\"algorithmVersion\":\"hrv-cam-v1\"")
                .doesNotContain(String.valueOf(USER_ID));
    }
}
