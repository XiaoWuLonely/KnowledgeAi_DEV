package com.knowflow.backend.knowledgebase.service;

import com.knowflow.backend.knowledgebase.entity.KnowledgeBase;
import com.knowflow.backend.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseAccessService {
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_VIEWER = "VIEWER";

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseAccessService(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * @Desc 确保用户是知识库的成员
     * @param knowledgeBaseId 知识库 ID
     * @param userId          用户 ID
     * @return 知识库实体，包含访问角色
     */
    public KnowledgeBase requireMember(Long knowledgeBaseId, Long userId) {
        return knowledgeBaseRepository.findAccessibleById(knowledgeBaseId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge base not found"));
    }

    /**
     * @Desc 确保用户是知识库的编辑者
     * @param knowledgeBaseId 知识库 ID
     * @param userId          用户 ID
     * @return 知识库实体，包含访问角色
     */
    public KnowledgeBase requireEditor(Long knowledgeBaseId, Long userId) {
        KnowledgeBase knowledgeBase = requireMember(knowledgeBaseId, userId);
        String role = knowledgeBase.getAccessRole();

        // VIEWER 可以查看、搜索和 Chat，但不能执行写操作。
        if (!ROLE_OWNER.equals(role) && !ROLE_EDITOR.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
        }
        return knowledgeBase;
    }

    /**
     * @Desc 确保用户是知识库的创建者
     * @param knowledgeBaseId 知识库 ID
     * @param userId          用户 ID
     * @return 知识库实体，包含访问角色
     */
    public KnowledgeBase requireOwner(Long knowledgeBaseId, Long userId) {
        KnowledgeBase knowledgeBase = requireMember(knowledgeBaseId, userId);

        // 成员存在但不是 OWNER 时返回 403，和非成员 404 区分开。
        if (!ROLE_OWNER.equals(knowledgeBase.getAccessRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
        }
        return knowledgeBase;
    }
}
