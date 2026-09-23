package com.github.xingjianluo.localobjectstoragejava.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@EnableScheduling
public class StorageConfiguration {
}
