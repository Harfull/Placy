package net.kyver.placy;

import net.kyver.placy.processor.FileProcessorRegistry;
import net.kyver.placy.processor.impl.TextFileProcessor;
import net.kyver.placy.util.PlaceholderTransformService;
import net.kyver.placy.util.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderTransformServiceTest {

    private PlaceholderTransformService service;
    private FileProcessorRegistry processorRegistry;

    @BeforeEach
    void setUp() {
        TextFileProcessor textProcessor = new TextFileProcessor();
        processorRegistry = new FileProcessorRegistry(List.of(textProcessor));

        Executor mockExecutor = Runnable::run;
        service = new PlaceholderTransformService(processorRegistry, mockExecutor);
    }

    @Test
    void testTransformTextFile() {
        String content = "Hello PLACEHOLDER_NAME, welcome to PLACEHOLDER_COMPANY!";
        MockMultipartFile file = new MockMultipartFile("test.txt", "test.txt", "text/plain", content.getBytes());
        Map<String, String> placeholders = Map.of(
            "PLACEHOLDER_NAME", "John Doe",
            "PLACEHOLDER_COMPANY", "Placy Corp"
        );

        TransformationResult result = service.transformFile(file, placeholders);

        assertTrue(result.isSuccess());
        assertNotNull(result.getContent());

        String resultText = new String(result.getContent());
        assertEquals("Hello John Doe, welcome to Placy Corp!", resultText);
    }

    @Test
    void testValidationFailure() {
        MockMultipartFile file = new MockMultipartFile("test.txt", "test.txt", "text/plain", new byte[0]);
        Map<String, String> placeholders = Map.of("TEST", "value");

        TransformationResult result = service.transformFile(file, placeholders);

        assertTrue(result.isSuccess());
    }

    @Test
    void testUnsupportedFileType() {
        String content = "Test content";
        MockMultipartFile file = new MockMultipartFile("test.unknown", "test.unknown", "application/unknown", content.getBytes());
        Map<String, String> placeholders = Map.of("TEST", "value");

        boolean isSupported = service.isFileSupported("test.unknown", "application/unknown");
        assertFalse(isSupported);
    }

    @Test
    void testEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("empty.txt", "empty.txt", "text/plain", new byte[0]);
        Map<String, String> placeholders = Map.of("TEST", "value");

        TransformationResult result = service.transformFile(file, placeholders);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getContent().length);
    }

    @Test
    void testFileSupport() {
        assertTrue(service.isFileSupported("test.txt", "text/plain"));
        assertFalse(service.isFileSupported("test.unknown", "application/unknown"));
    }
}
