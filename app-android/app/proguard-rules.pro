# kotlinx.serialization: @Serializable 클래스의 직렬화기 보존
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers,allowshrinking class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# 접근성 서비스는 시스템이 이름으로 바인딩하므로 난독화에서 제외
-keep class com.davi.helper.GuideAccessibilityService { *; }
