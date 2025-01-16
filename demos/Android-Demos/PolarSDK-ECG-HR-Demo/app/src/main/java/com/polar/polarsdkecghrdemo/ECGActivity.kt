package com.polar.polarsdkecghrdemo

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYPlot
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*
import kotlin.math.pow
//import kotlin.math.pow
import kotlin.math.sin


class ECGActivity : AppCompatActivity(), PlotterListener {
    companion object {
        private const val TAG = "ECGActivity"
    }

    private lateinit var api: PolarBleApi
    private lateinit var textViewHR: TextView
    private lateinit var textViewRR: TextView
    private lateinit var textViewDeviceId: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewFwVersion: TextView
    private lateinit var plot: XYPlot
    private lateinit var ecgPlotter: EcgPlotter
    private var ecgDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null

    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg)
        deviceId = intent.getStringExtra("id")
            ?: throw Exception("ECGActivity couldn't be created, no deviceId given")
        textViewHR = findViewById(R.id.hr)
        textViewRR = findViewById(R.id.rr)
        textViewDeviceId = findViewById(R.id.deviceId)
        textViewBattery = findViewById(R.id.battery_level)
        textViewFwVersion = findViewById(R.id.fw_version)
        plot = findViewById(R.id.plot)

        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected " + polarDeviceInfo.deviceId)
                Toast.makeText(applicationContext, R.string.connected, Toast.LENGTH_SHORT).show()
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connecting ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device disconnected ${polarDeviceInfo.deviceId}")
            }

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                Log.d(TAG, "feature ready $feature")

                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        streamECG()
                        streamHR()
                    }

                    else -> {}
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    val msg = "Firmware: " + value.trim { it <= ' ' }
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                    textViewFwVersion.append(msg.trimIndent())
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "Battery level $identifier $level%")
                val batteryLevelText = "Battery level: $level%"
                textViewBattery.append(batteryLevelText)
            }

            @Deprecated("Please use the startHrStreaming API to get the heart rate data ")
            override fun hrNotificationReceived(
                identifier: String,
                data: PolarHrData.PolarHrSample
            ) {
                // deprecated
            }

            @Deprecated("Not supported anymore, won't be ever called. Use the bleSdkFeatureReady")
            override fun polarFtpFeatureReady(identifier: String) {
                // deprecated
            }

            @Deprecated("The functionality has changed. Please use the bleSdkFeatureReady to know if onlineStreaming is available and the getAvailableOnlineStreamDataTypes function know which data types are supported")
            override fun streamingFeaturesReady(
                identifier: String,
                features: Set<PolarBleApi.PolarDeviceDataType>
            ) {
                // deprecated
            }

            @Deprecated("Information whether HR feature is available is provided by bleSdkFeatureReady")
            override fun hrFeatureReady(identifier: String) {
                // deprecated
            }

        })
        try {
            api.connectToDevice(deviceId)
        } catch (a: PolarInvalidArgument) {
            a.printStackTrace()
        }
        val deviceIdText = "ID: $deviceId"
        textViewDeviceId.text = deviceIdText

        ecgPlotter = EcgPlotter("ECG", 130)
        ecgPlotter.setListener(this)

        plot.addSeries(ecgPlotter.getSeries(), ecgPlotter.formatter)
        plot.setRangeBoundaries(-1.5, 1.5, BoundaryMode.FIXED)
        plot.setRangeStep(StepMode.INCREMENT_BY_FIT, 0.25)
        plot.setDomainStep(StepMode.INCREMENT_BY_VAL, 130.0)
        plot.setDomainBoundaries(0, 650, BoundaryMode.FIXED)
        plot.linesPerRangeLabel = 2
    }

    public override fun onDestroy() {
        super.onDestroy()
        ecgDisposable?.let {
            if (!it.isDisposed) it.dispose()
        }
        api.shutDown()
    }

    fun streamECG() {
        val sampleRate = 22050 // Hz
        val samplesPerData = sampleRate / 130 // Polar H10 ECG at 130 Hz
        val buffer = ShortArray(sampleRate * 3)
        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, sampleRate * 10, AudioTrack.MODE_STREAM
        )
        var audioPlayDelay = 0
        val twoPi = 2.0 * Math.PI

        //var carrierPhase = 0.0
        //val carrierFreq = 55

        val isDisposed = ecgDisposable?.isDisposed ?: true
        if (isDisposed) {
            var sampleIndex = 0
            ecgDisposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
                .toFlowable()
                .flatMap { sensorSetting: PolarSensorSetting ->
                    api.startEcgStreaming(
                        deviceId,
                        sensorSetting.maxSettings()
                    )
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { polarEcgData: PolarEcgData ->


//                        var bufferIndex = 0
//                        for (data in polarEcgData.samples) {
//                            var volts = data.voltage / 1000.0
//                            ecgPlotter.sendSingleSample(volts.toFloat())
//                            volts += 0.5
//                            if (volts > 2) {
//                                volts = 2.0
//                            }
//                            if (volts < 0) {
//                                volts = 0.0
//                            }
//                            val modulationFreq = 440 * 2.0.pow(volts)
//                            for (i in 0 until samplesPerData) {
//                                val modulator =
//                                    sin(twoPi * modulationFreq * sampleIndex / sampleRate)
//                                carrierPhase += twoPi * carrierFreq / sampleRate + 0.5 * modulator
//                                val sample = sin(carrierPhase)
//                                buffer[bufferIndex] = (sample * Short.MAX_VALUE).toInt().toShort()
//                                bufferIndex += 1
//                                sampleIndex += 1
//                            }
//                        }
//                        audioTrack.write(buffer, 0, bufferIndex);
//                        if (audioPlayDelay <= 0) {
//                            audioTrack.play()
//                        } else {
//                            audioPlayDelay -= 1
//                        }


                        var bufferIndex = 0
                        for (data in polarEcgData.samples) {
                            var volts = data.voltage / 1000.0
                            // ecgPlotter.sendSingleSample(volts.toFloat())
                            volts += 0.5
                            if (volts > 2) {
                                volts = 2.0
                            }
                            if (volts < 0) {
                                volts = 0.0
                            }
                            val tone = 880 * 2.0.pow(volts)
                            for (i in 0 until samplesPerData) {
                                val sample = sin(twoPi * sampleIndex * tone / sampleRate)
                                buffer[bufferIndex] = (sample * Short.MAX_VALUE).toInt().toShort()
                                bufferIndex += 1
                                sampleIndex += 1
                            }
                        }
                        audioTrack.write(buffer, 0, bufferIndex)
                        if (audioPlayDelay <= 0) {
                            audioTrack.play()
                        } else {
                            audioPlayDelay -= 1
                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "Ecg stream failed $error")
                        ecgDisposable = null
                    },
                    {
                        Log.d(TAG, "Ecg stream complete")
                    }
                )
        } else {
            // NOTE stops streaming if it is "running"
            ecgDisposable?.dispose()
            ecgDisposable = null
            audioTrack.pause()
            audioTrack.release()
        }
    }

    fun streamHR() {
        val isDisposed = hrDisposable?.isDisposed ?: true
        if (isDisposed) {
            hrDisposable = api.startHrStreaming(deviceId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { hrData: PolarHrData ->
                        for (sample in hrData.samples) {
                            Log.d(TAG, "HR " + sample.hr)
                            if (sample.rrsMs.isNotEmpty()) {
                                val rrText = "(${sample.rrsMs.joinToString(separator = "ms, ")}ms)"
                                textViewRR.text = rrText
                            }

                            textViewHR.text = sample.hr.toString()

                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "HR stream failed. Reason $error")
                        hrDisposable = null
                    },
                    { Log.d(TAG, "HR stream complete") }
                )
        } else {
            // NOTE stops streaming if it is "running"
            hrDisposable?.dispose()
            hrDisposable = null
        }
    }

    override fun update() {
        runOnUiThread { plot.redraw() }
    }
}