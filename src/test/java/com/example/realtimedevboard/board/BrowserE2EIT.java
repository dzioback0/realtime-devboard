package com.example.realtimedevboard.board;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end through the browser UI: login, click Run on the seeded Java block, real result in the console;
 * a second browser session of another workspace member sees the result and new elements in real time.
 * Requires Docker, Chrome and the built client (npm --prefix frontend run build).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BrowserE2EIT {

    @LocalServerPort
    int port;
    private final List<WebDriver> drivers = new ArrayList<>();

    @AfterEach
    void quit() {
        drivers.forEach(WebDriver::quit);
    }

    private WebDriver loggedIn(String user) {
        ChromeOptions options = new ChromeOptions().addArguments("--headless=new", "--window-size=1400,900");
        WebDriver driver = new ChromeDriver(options);
        drivers.add(driver);
        driver.get("http://localhost:" + port + "/");
        driver.findElement(By.name("username")).sendKeys(user);
        driver.findElement(By.name("password")).sendKeys(BoardApiTest.PASSWORD);
        driver.findElement(By.cssSelector("button[type=submit]")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".el.CODE")));
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.attributeContains(By.id("live"), "class", "on"));
        return driver;
    }

    @Test
    void runJavaBlockFromBoardAndCollaborate() {
        assertThat(Files.exists(Path.of("src/main/resources/static/app.js")))
                .as("client not built: run `npm --prefix frontend ci && npm --prefix frontend run build`").isTrue();
        WebDriver alice = loggedIn("alice");
        WebDriver adam = loggedIn("adam");

        alice.findElement(By.cssSelector(".el.CODE .run")).click();
        By status = By.cssSelector(".el.CODE .console .status");
        String finalStatus = new WebDriverWait(alice, Duration.ofSeconds(60)).until(d -> {
            String text = d.findElement(status).getText();
            return text.startsWith("RUNNING") || text.contains("nevykdyta") ? null : text;
        });
        assertThat(finalStatus).startsWith("SUCCESS");
        assertThat(alice.findElement(By.cssSelector(".el.CODE .console pre.stdout")).getText()).isEqualTo("Hello");

        // the other session got the execution result over the WebSocket, without reloading
        new WebDriverWait(adam, Duration.ofSeconds(10)).until(d -> d.findElement(status).getText().startsWith("SUCCESS"));
        assertThat(adam.findElement(By.cssSelector(".el.CODE .console pre.stdout")).getText()).isEqualTo("Hello");

        int before = adam.findElements(By.cssSelector(".el")).size();
        alice.findElement(By.cssSelector("[data-add=TEXT]")).click();
        new WebDriverWait(adam, Duration.ofSeconds(10)).until(d -> d.findElements(By.cssSelector(".el")).size() == before + 1);

        // persisted: reload shows the stored result
        adam.navigate().refresh();
        WebElement console = new WebDriverWait(adam, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOfElementLocated(status));
        assertThat(console.getText()).startsWith("SUCCESS");
    }
}
