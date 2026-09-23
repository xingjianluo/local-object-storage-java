package com.github.xingjianluo.localobjectstoragejava.web;

import com.github.xingjianluo.localobjectstoragejava.service.MultipartUploadService;
import com.github.xingjianluo.localobjectstoragejava.service.ObjectService;
import com.github.xingjianluo.localobjectstoragejava.service.StorageAdminService;
import com.github.xingjianluo.localobjectstoragejava.web.dto.Dtos;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地对象存储 REST 入口。
 */
@RestController
public class StorageController {

    public StorageController(MultipartUploadService multipartUploadService,
                             ObjectService objectService,
                             StorageAdminService adminService) {
    }

    public Dtos.SessionResponse createSession(Dtos.CreateSessionRequest request) {
        return null;
    }
}
