#Go Ubiquitous

##What is it?

This is an Android wear watch face for an existing Android weather app.

##Platform and Libraries Used

- Android SDK 21 or Higher
- Build Tools version 22.0.1
- Android Support AppCompat 22.2.0
- Android Support Annotations 22.2.0
- Android Support GridLayout 22.2.0
- Android Support CardView 22.2.0
- Android Support Design 22.2.0
- Android Support RecyclerView 22.2.0
- Google Play Services GCM 8.3.0
- Google Play Services Wearable 8.3.0
- BumpTech Glide 3.5.2

##Installation Instructions

This project uses the Gradle build automation system. To build this project, 
use the "gradlew build" command or use "Import Project" in Android Studio.

The app uses the following API key that must be specified in the build.gradle 
file of the app module:

1. Open Weather Map API Key: This is used in the query to fetch the weather
data from the Open Weather Map API and can be generated 
[here](http://openweathermap.org/appid).

##Attribution

The weather data is provided by the [Open Weather Map API](http://openweathermap.org/api)

The weather app code was provided by [Udacity](https://github.com/udacity/
Advanced_Android_Development/tree/7.05_Pretty_Wallpaper_Time).  