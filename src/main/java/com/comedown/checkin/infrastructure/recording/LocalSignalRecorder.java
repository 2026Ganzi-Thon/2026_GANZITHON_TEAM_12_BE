package com.comedown.checkin.infrastructure.recording;

import com.comedown.checkin.application.port.SignalRecorder;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.domain.measurement.HrvAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * local 프로필 전용: 임계값 조정을 위해 측정 원신호와 계산 결과를 파일로 남긴다. 기본 꺼짐.
 * 켜기: {@code comedown.hrv.signal-recording.enabled=true}. 저장 위치는 Git 제외 경로(기본 {@code recordings/}).
 * 사용자 식별자는 넣지 않는다. 기록 실패는 측정 저장을 막지 않는다.
 */
@Component
@Profile("local")
class LocalSignalRecorder implements SignalRecorder {

    private static final Logger log = LoggerFactory.getLogger(LocalSignalRecorder.class);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final boolean enabled;
    private final Path directory;
    private final JsonMapper jsonMapper;

    LocalSignalRecorder(
            @Value("${comedown.hrv.signal-recording.enabled:false}") boolean enabled,
            @Value("${comedown.hrv.signal-recording.dir:recordings}") String directory,
            JsonMapper jsonMapper) {
        this.enabled = enabled;
        this.directory = Path.of(directory);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void record(long measurementId, RecordCameraMeasurement command, HrvAnalyzer.Result result) {
        if (!enabled) {
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("measurementId", measurementId);
        content.put("startedAt", command.startedAt().toString());
        content.put("torch", command.torch());
        content.put("timeSource", command.timeSource());
        content.put("algorithmVersion", HrvAnalyzer.ALGORITHM_VERSION);
        content.put("result", result);
        content.put("t", command.t());
        content.put("r", command.r());
        content.put("g", command.g());
        Path file = directory.resolve(
                FILE_TIME.format(command.startedAt()) + "-m" + measurementId + ".hrv-recording.json");
        try {
            Files.createDirectories(directory);
            jsonMapper.writeValue(file.toFile(), content);
            log.info("HRV signal recorded for tuning: {}", file.getFileName());
        } catch (IOException | RuntimeException e) {
            log.warn("HRV signal recording failed: {}", e.getClass().getSimpleName());
        }
    }
}
