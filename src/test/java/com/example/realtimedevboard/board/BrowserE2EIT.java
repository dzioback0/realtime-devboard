package com.example.realtimedevboard.board;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
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

    /** The UI re-renders consoles on every socket message, so a looked-up node may go stale mid-check. */
    private static WebDriverWait waitFor(WebDriver driver, int seconds) {
        return (WebDriverWait) new WebDriverWait(driver, Duration.ofSeconds(seconds)).ignoring(StaleElementReferenceException.class);
    }

    private WebDriver loggedIn(String user) {
        ChromeOptions options = new ChromeOptions().addArguments("--headless=new", "--window-size=1400,900");
        WebDriver driver = new ChromeDriver(options);
        drivers.add(driver);
        driver.get("http://localhost:" + port + "/");
        driver.findElement(By.name("username")).sendKeys(user);
        driver.findElement(By.name("password")).sendKeys(BoardApiTest.PASSWORD);
        driver.findElement(By.cssSelector("button[type=submit]")).click();
        waitFor(driver, 10)
                .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".el.CODE")));
        waitFor(driver, 10)
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
        String finalStatus = waitFor(alice, 60).until(d -> {
            String text = d.findElement(status).getText();
            return text.startsWith("RUNNING") || text.contains("nevykdyta") ? null : text;
        });
        assertThat(finalStatus).startsWith("SUCCESS");
        assertThat(alice.findElement(By.cssSelector(".el.CODE .console pre.stdout")).getText()).isEqualTo("Hello");

        // the other session got the execution result over the WebSocket, without reloading
        waitFor(adam, 10).until(d -> d.findElement(status).getText().startsWith("SUCCESS"));
        assertThat(adam.findElement(By.cssSelector(".el.CODE .console pre.stdout")).getText()).isEqualTo("Hello");

        int before = adam.findElements(By.cssSelector(".el")).size();
        alice.findElement(By.cssSelector("[data-add=TEXT]")).click();
        waitFor(adam, 10).until(d -> d.findElements(By.cssSelector(".el")).size() == before + 1);

        // a circle has no text field; resizing it by the corner handle reaches the other session
        alice.findElement(By.cssSelector("[data-add=SHAPE][data-shape=CIRCLE]")).click();
        WebElement circle = waitFor(alice, 10)
                .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".el.SHAPE.CIRCLE")));
        assertThat(circle.findElements(By.tagName("textarea"))).isEmpty();
        new Actions(alice).dragAndDropBy(circle.findElement(By.cssSelector(".resize")), 80, 40).perform();
        waitFor(adam, 10).until(d -> d.findElements(By.cssSelector(".el.SHAPE.CIRCLE")).stream()
                .anyMatch(c -> c.getCssValue("width").equals("200px") && c.getCssValue("height").equals("160px")));

        // persisted: reload shows the stored result
        adam.navigate().refresh();
        WebElement console = waitFor(adam, 10)
                .until(ExpectedConditions.visibilityOfElementLocated(status));
        assertThat(console.getText()).startsWith("SUCCESS");
    }
}
