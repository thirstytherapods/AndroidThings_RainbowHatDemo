package things.wolfsoft.com.androidthings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.pwmspeaker.Speaker;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ThingActivity extends AppCompatActivity {
    private static final String TAG = "ThingActivity";

    private Button buttonA;
    private Button buttonB;
    private Button buttonC;

    private Gpio ledGpioRed;
    private Gpio ledGpioBlue;
    private Gpio ledGpioGreen;
    private static int RED_LED = 1;
    private static int BLUE_LED = 2;
    private static int GREEN_LED = 3;

    private Apa102 ledstrip;
    private int NUM_LEDS = 7;
    private int[] mRainbow = new int[NUM_LEDS];
    private static final int LEDSTRIP_BRIGHTNESS = 1;
    private boolean rainbowOrder = true;

    private AlphanumericDisplay alphaDisplay;
    private static final float CLEAR_DISPLAY = 73638.45f;
    private enum DisplayMode {
        DOOR,
        IN,
        OUT,
        CLEAR
    }
    private DisplayMode displayMode = DisplayMode.DOOR;

    private Speaker speaker;
    private int SPEAKER_READY_DELAY_MS = 300;
    private boolean isSpeakerMute = false;
    private static int SOUND_LOW = 1;
    private static int SOUND_MED = 4;
    private static int SOUND_HIGH = 8;

    private TextView titleTxt;

    private AppTextToSpeech appTextToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thing);

        Log.d(TAG, "Hello Android Things!");
        titleTxt = (TextView) findViewById(R.id.text_title);

        // Set current IP on display (need this to connect ADB)
        String currentIp = getIPAddress(true);

        titleTxt.setText("Current IP (time started):\n    " + currentIp + "\n    " + Utilities.getDate());
        Log.d(TAG, "Current IP address is: " + currentIp);

        appTextToSpeech = new AppTextToSpeech(this);

        // Initialize buttons
        try {
            buttonA = new Button(BoardDefaults.getGPIOForBtnA(),
                    Button.LogicState.PRESSED_WHEN_LOW);
            buttonA.setOnButtonEventListener(buttonCallbackA);

            buttonB = new Button(BoardDefaults.getGPIOForBtnB(),
                    Button.LogicState.PRESSED_WHEN_LOW);
            buttonB.setOnButtonEventListener(buttonCallbackB);

            buttonC = new Button(BoardDefaults.getGPIOForBtnC(),
                    Button.LogicState.PRESSED_WHEN_LOW);
            buttonC.setOnButtonEventListener(buttonCallbackC);
        } catch (IOException e) {
            Log.e(TAG, "button driver error", e);
        }

        //GPIO Individual Color LED
        try {
            PeripheralManagerService service = new PeripheralManagerService();
            ledGpioRed = service.openGpio(BoardDefaults.getGPIOForRedLED());
            ledGpioGreen = service.openGpio(BoardDefaults.getGPIOForGreenLED());
            ledGpioBlue = service.openGpio(BoardDefaults.getGPIOForBlueLED());
        } catch (IOException e) {
            throw new RuntimeException("Problem connecting to IO Port", e);
        }

        //SPI LED Lightstrip and rainbow color array
        for (int i = 0; i < NUM_LEDS; i++) {
            float[] hsv = {i * 360.f / NUM_LEDS, 1.0f, 1.0f};
            mRainbow[i] = Color.HSVToColor(255, hsv);
        }
        try {
            ledstrip = new Apa102(BoardDefaults.getSpiBus(), Apa102.Mode.BGR);
            ledstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
        } catch (IOException e) {
            ledstrip = null; // Led strip is optional.
        }

        // Alphanumeric Display
        try {
            alphaDisplay = new AlphanumericDisplay(BoardDefaults.getI2cBus());
            alphaDisplay.setEnabled(true);
            alphaDisplay.clear();
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing display", e);
            Log.d(TAG, "Display disabled");
            alphaDisplay = null;
        }

        // PWM speaker
        try {
            speaker = new Speaker(BoardDefaults.getSpeakerPwmPin());
            soundSpeaker(1);
            Log.d(TAG, "Initialized PWM speaker");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing PWM speaker", e);
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //Buttons
        if (buttonA != null) {
            // TODO
        }

        if (buttonB != null) {
            // TODO
        }

        if (buttonC != null) {
            // TODO
        }

        // GPIO LEDS
        try {
            ledGpioRed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            ledGpioBlue.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            ledGpioGreen.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);

            ledGpioRed.close();
            ledGpioBlue.close();
            ledGpioGreen.close();
        } catch (IOException e) {
            Log.e(TAG, "Error on PeripheralIO API", e);
        }

        // LED Lightstrip
        try {
            if (ledstrip != null) {
                try {
                    ledstrip.write(new int[NUM_LEDS]);
                    ledstrip.setBrightness(0);
                    ledstrip.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error disabling ledstrip", e);
                } finally {
                    ledstrip = null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error on closing LED strip", e);
        }

        // Alphanumeric Display
        if (alphaDisplay != null) {
            try {
                alphaDisplay.clear();
                alphaDisplay.setEnabled(false);
                alphaDisplay.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling display", e);
            } finally {
                alphaDisplay = null;
            }
        }

    }

    /**
     * Callback for buttonA events.
     */
    private Button.OnButtonEventListener buttonCallbackA =
            new Button.OnButtonEventListener() {
                @Override
                public void onButtonEvent(Button button, boolean pressed) {
                    if (pressed) {
                        Log.d(TAG, "The button A event was received KEY DOWN");
                        displayMode = DisplayMode.DOOR;
                        updateDisplay(CLEAR_DISPLAY);
                        int[] colors = new int[NUM_LEDS];
                        // Switches the rainbow from left to right on each press
                        if (rainbowOrder) {
                            rainbowOrder = false;
                            colors[0] = mRainbow[6];
                            colors[1] = mRainbow[5];
                            colors[2] = mRainbow[4];
                            colors[3] = mRainbow[3];
                            colors[4] = mRainbow[2];
                            colors[5] = mRainbow[1];
                            colors[6] = mRainbow[0];
                        } else {
                            rainbowOrder = true;
                            colors[0] = mRainbow[0];
                            colors[1] = mRainbow[1];
                            colors[2] = mRainbow[2];
                            colors[3] = mRainbow[3];
                            colors[4] = mRainbow[4];
                            colors[5] = mRainbow[5];
                            colors[6] = mRainbow[6];
                        }
                        //soundSpeaker(SOUND_LOW);
                        runLedStrip(colors);
                        showLED(RED_LED);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                //appTextToSpeech.say("DOOR");
                            }
                        }).start();

                        int randIndex = randInt(0, 5);
                        try {
                            JSONObject requests = new JSONObject();

                            JSONArray arrRequest = new JSONArray();
                            JSONObject image = new JSONObject();
                            JSONObject source = new JSONObject();
                            JSONObject imageUri = new JSONObject();
                            imageUri.put("imageUri", Constants.IMAGES[randIndex]);
                            source.put("source", imageUri);
                            image.put("image", source);
                            JSONArray arrFeatures = new JSONArray();
                            JSONObject feature = new JSONObject();
                            feature.put("type", "WEB_DETECTION");
                            feature.put("maxResults", 2);
                            arrFeatures.put(0, feature);
                            //JSONObject features = new JSONObject();
                            image.put("features", arrFeatures);
                            //image.put("features", features);


                            arrRequest.put(0, image);
                            requests.put("requests", arrRequest);

                            new UploadFileTask().execute(requests.toString());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            };

    /**
     * Callback for buttonB events.
     */
    private Button.OnButtonEventListener buttonCallbackB =
            new Button.OnButtonEventListener() {
                @Override
                public void onButtonEvent(Button button, boolean pressed) {
                    if (pressed) {
                        if(displayMode == DisplayMode.OUT) {
                            displayMode = DisplayMode.IN;
                        } else {
                            displayMode = DisplayMode.OUT;
                        }

                        updateDisplay(CLEAR_DISPLAY);
                        Log.d(TAG, "button B pressed");
                        Random rand = new Random();
                        int[] colors = new int[NUM_LEDS];
                        colors[0] = mRainbow[rand.nextInt(NUM_LEDS)];
                        colors[1] = mRainbow[rand.nextInt(NUM_LEDS)];
                        colors[2] = mRainbow[rand.nextInt(NUM_LEDS)];
                        colors[3] = mRainbow[rand.nextInt(NUM_LEDS)];
                        colors[4] = mRainbow[rand.nextInt(NUM_LEDS)];
                        colors[5] = mRainbow[rand.nextInt(NUM_LEDS)];
                        colors[6] = mRainbow[rand.nextInt(NUM_LEDS)];

                        soundSpeaker(SOUND_MED);
                        runLedStrip(colors);
                        showLED(GREEN_LED);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                appTextToSpeech.say(displayMode.name());
                            }
                        }).start();
                    }
                }
            };

    /**
     * Callback for buttonC events.
     */
    private Button.OnButtonEventListener buttonCallbackC =
            new Button.OnButtonEventListener() {
                @Override
                public void onButtonEvent(Button button, boolean pressed) {
                    if (pressed) {
                        Log.d(TAG, "button C pressed");
                        displayMode = DisplayMode.CLEAR;
                        updateDisplay(CLEAR_DISPLAY);
                        soundSpeaker(SOUND_HIGH);
                        clearLedStrip();
                        showLED(BLUE_LED);
                    }
                }
            };

    /**
     * Helper Method to turn one of 3 LEDs, and turn off the others
     * @param ledType
     */
    private void showLED(int ledType) {
        try {
            ledGpioRed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            ledGpioBlue.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            ledGpioGreen.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);

            switch (ledType) {
                case 1:
                    ledGpioRed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
                    break;
                case 2:
                    ledGpioBlue.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
                    break;
                case 3:
                    ledGpioGreen.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
                    break;

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void runLedStrip(int[] colors) {
        try {
            ledstrip.write(colors);
            ledstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
        } catch (IOException e) {
            Log.e(TAG, "Error setting ledstrip", e);
        }
    }

    private void clearLedStrip() {
        try {
            ledstrip.write(new int[NUM_LEDS]);
            ledstrip.setBrightness(0);
        } catch (IOException e) {
            Log.e(TAG, "Error setting ledstrip", e);
        }
    }

    private void soundSpeaker(int soundType) {
        if (!isSpeakerMute) {
            int soundVal = soundType * 100;

            final ValueAnimator slide = ValueAnimator.ofFloat(soundVal, 440 * 4);

            slide.setDuration(50);
            slide.setRepeatCount(5);
            slide.setInterpolator(new LinearInterpolator());
            slide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    try {
                        float v = (float) animation.getAnimatedValue();
                        speaker.play(v);
                    } catch (IOException e) {
                        throw new RuntimeException("Error sliding speaker", e);
                    }
                }
            });
            slide.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        speaker.stop();
                    } catch (IOException e) {
                        throw new RuntimeException("Error sliding speaker", e);
                    }
                }
            });
            Handler handler = new Handler(getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    slide.start();
                }
            }, SPEAKER_READY_DELAY_MS);
        }
    }

    private void updateDisplay(float value) {
        if (alphaDisplay != null) {
            try {
                if (displayMode == DisplayMode.DOOR) {
                    alphaDisplay.display("DOOR");
                } else if(displayMode == DisplayMode.IN) {
                    alphaDisplay.display("IN");
                } else if(displayMode == DisplayMode.OUT) {
                    alphaDisplay.display("OUT");
                } else if (displayMode == DisplayMode.CLEAR) {
                    alphaDisplay.clear();
                } else {
                    alphaDisplay.display(value);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error setting display", e);
            }
        }
    }

    /**
     * A utility method to return current IP
     *
     * @param useIPv4
     * @return
     */
    private static String getIPAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (useIPv4) {
                            if (isIPv4)
                                return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                                return delim < 0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception received getting IP info: " + ex, ex);
        }
        return "NO IP ADDRESS FOUND";
    }

    private int randInt(int min, int max) {

        Random rand = new Random();

        int randomNum = rand.nextInt((max - min) + 1) + min;

        return randomNum;
    }

    private class UploadFileTask extends AsyncTask<String, Integer, String> {

        private long totalSize = 0;

        @Override
        protected String doInBackground(String... payload) {
            Log.d(TAG, payload[0]);
            String responseString = null;

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter());
            restTemplate.getMessageConverters().add(new StringHttpMessageConverter());

            List<MediaType> acceptableMediaTypes = new ArrayList<>();
            acceptableMediaTypes.add(MediaType.APPLICATION_JSON);

            // Prepare header
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(acceptableMediaTypes);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Connection", "Close");
            // Pass the new person and header
            HttpEntity entity = new HttpEntity(payload[0], headers);

            responseString = restTemplate.exchange(Constants.GOOGLE_VISION_URL + "?" +
                            Constants.PARAM_KEY + "=" + Constants.GOOGLE_VISION_API_KEY,
                    HttpMethod.POST, entity, String.class).getBody();

            Log.d(TAG, responseString);

            return responseString;
        }

        @Override
        protected void onPostExecute(String result) {
            if(result != null && !"".equals(result)) {
                try {
                    JSONObject response = new JSONObject(result);
                    JSONArray responses = response.getJSONArray("responses");
                    JSONObject webDetection = responses.getJSONObject(0).getJSONObject("webDetection");
                    JSONArray webEntities = webDetection.getJSONArray("webEntities");

                    for(int i = 0; i < webEntities.length(); i++) {
                        JSONObject webEntity = webEntities.getJSONObject(i);
                        if(webEntity.getString("description").contains("Anger") &&
                                webEntity.getDouble("score") > 0.5) {
                            displayMode = DisplayMode.OUT;
                            updateDisplay(CLEAR_DISPLAY);
                        } else {
                            displayMode = DisplayMode.IN;
                            updateDisplay(CLEAR_DISPLAY);
                        }
                    }

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
