package com.adropofliquid.wtw.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.io.FileInputStream

/** prod only: Firebase Admin from the service-account file at `FIREBASE_CREDENTIALS_PATH` (token check + FCM). */
@Configuration
@Profile("prod")
class FirebaseConfig {
    @Bean
    fun firebaseApp(props: WtwProperties): FirebaseApp {
        val path = props.auth.firebaseCredentialsPath
        require(path.isNotBlank()) { "FIREBASE_CREDENTIALS_PATH is required in prod" }
        FirebaseApp.getApps().firstOrNull()?.let { return it }
        val creds = FileInputStream(path).use { GoogleCredentials.fromStream(it) }
        return FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(creds).build())
    }
}
