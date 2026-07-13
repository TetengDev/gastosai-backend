package com.teng.app.gastosai;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class GastosaiApplication {

	public static void main(String[] args) {
		loadDotenv();
		SpringApplication.run(GastosaiApplication.class, args);
	}

	/**
	 * Loads {@code .env} from the JVM working directory, the repo root (parent directory, when run
	 * from {@code backend/}), or {@code gastosai/.env} when the IDE uses the repo root as cwd.
	 */
	private static void loadDotenv() {
		Path envFile = resolveDotenvFile();
		Dotenv dotenv = envFile != null
				? Dotenv.configure().directory(envFile.getParent().toString()).ignoreIfMissing().load()
				: Dotenv.configure().ignoreIfMissing().load();
		dotenv.entries().forEach(entry -> {
			String key = entry.getKey();
			if (System.getenv(key) == null && System.getProperty(key) == null) {
				System.setProperty(key, entry.getValue());
			}
		});
	}

	private static Path resolveDotenvFile() {
		String userDir = System.getProperty("user.dir");
		Path[] candidates = new Path[] {
				Path.of(userDir, ".env"),
				Path.of(userDir, "..", ".env").normalize(),
				Path.of(userDir, "gastosai", ".env"),
		};
		for (Path p : candidates) {
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}
}
