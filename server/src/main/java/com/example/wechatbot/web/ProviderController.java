package com.example.wechatbot.web;

import com.example.wechatbot.service.ProviderAdminService;
import com.example.wechatbot.web.dto.AdminDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/providers")
public class ProviderController {
    private final ProviderAdminService service;

    public ProviderController(ProviderAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminDtos.ProviderView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.ProviderView create(@Valid @RequestBody AdminDtos.ProviderCreate request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public AdminDtos.ProviderView update(@PathVariable UUID id,
                                         @Valid @RequestBody AdminDtos.ProviderUpdate request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    public AdminDtos.ProviderTestResponse test(@PathVariable UUID id) {
        return service.test(id);
    }
}
