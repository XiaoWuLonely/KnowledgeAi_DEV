import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import {
  Bot,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  FileText,
  Loader2,
  Send,
  X,
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Textarea } from "@/components/ui/textarea";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

export type ChatComposerPayload = {
  message: string;
  modelId: string;
  ragEnabled: boolean;
  mentionedDocumentIds: number[];
};

export type ChatMentionDocument = {
  id: number;
  name: string;
};

type ChatModel = {
  id: string;
  label: string;
};

type ChatComposerProps = {
  placeholder: string;
  onSubmit: (payload: ChatComposerPayload) => void | Promise<void>;
  className?: string;
  disabled?: boolean;
  isSubmitting?: boolean;
  submitLabel?: string;
  showContextControls?: boolean;
  helperText?: string;
  modelOptions?: ChatModel[];
  selectedModelId?: string;
  onModelChange?: (modelId: string) => void;
  isLoadingModels?: boolean;
  controlsDisabled?: boolean;
  onCancel?: () => void | Promise<void>;
  mentionDocuments?: ChatMentionDocument[];
  isLoadingMentionDocuments?: boolean;
};

const MAX_CHAT_MESSAGE_LENGTH = 4000;
const MENTION_PAGE_SIZE = 6;
const CHAT_INPUT_MIN_HEIGHT = 72;
const CHAT_INPUT_DEFAULT_HEIGHT = 96;
const CHAT_INPUT_MOBILE_DEFAULT_HEIGHT = CHAT_INPUT_MIN_HEIGHT;
const CHAT_INPUT_DESKTOP_MAX_VIEWPORT_RATIO = 0.4;
const CHAT_INPUT_MOBILE_MAX_VIEWPORT_RATIO = 0.28;

type ChatInputResizeState = {
  pointerId: number;
  startY: number;
  startHeight: number;
};

type MentionTrigger = {
  start: number;
  end: number;
  query: string;
};

function getChatInputMaxHeight() {
  if (typeof window === "undefined") {
    return 320;
  }

  const maxViewportRatio =
    window.innerWidth < 640
      ? CHAT_INPUT_MOBILE_MAX_VIEWPORT_RATIO
      : CHAT_INPUT_DESKTOP_MAX_VIEWPORT_RATIO;

  return Math.max(
    CHAT_INPUT_MIN_HEIGHT,
    Math.floor(window.innerHeight * maxViewportRatio),
  );
}

function getChatInputDefaultHeight() {
  if (typeof window === "undefined") {
    return CHAT_INPUT_DEFAULT_HEIGHT;
  }

  return window.innerWidth < 640
    ? CHAT_INPUT_MOBILE_DEFAULT_HEIGHT
    : CHAT_INPUT_DEFAULT_HEIGHT;
}

function clampChatInputHeight(value: number) {
  return Math.min(
    Math.max(value, CHAT_INPUT_MIN_HEIGHT),
    getChatInputMaxHeight(),
  );
}

function getMentionTrigger(
  value: string,
  cursorPosition: number,
): MentionTrigger | null {
  const beforeCursor = value.slice(0, cursorPosition);
  const atIndex = beforeCursor.lastIndexOf("@");

  if (atIndex < 0) {
    return null;
  }

  const previousCharacter = atIndex === 0 ? "" : value[atIndex - 1];

  if (previousCharacter && !/\s/.test(previousCharacter)) {
    return null;
  }

  const token = beforeCursor.slice(atIndex + 1);

  if (/\s/.test(token)) {
    return null;
  }

  return {
    start: atIndex,
    end: cursorPosition,
    query: token,
  };
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function hasMentionToken(value: string, documentName: string) {
  return new RegExp(
    `(^|\\s)@${escapeRegExp(documentName)}(?=\\s|$)`,
    "u",
  ).test(value);
}

function highlightMatch(name: string, query: string) {
  const normalizedQuery = query.trim().toLowerCase();

  if (!normalizedQuery) {
    return name;
  }

  const matchIndex = name.toLowerCase().indexOf(normalizedQuery);

  if (matchIndex < 0) {
    return name;
  }

  return (
    <>
      {name.slice(0, matchIndex)}
      <mark className="rounded-[3px] bg-amber-100 px-0.5 text-amber-900">
        {name.slice(matchIndex, matchIndex + normalizedQuery.length)}
      </mark>
      {name.slice(matchIndex + normalizedQuery.length)}
    </>
  );
}

export function ChatComposer({
  placeholder,
  onSubmit,
  className,
  disabled = false,
  isSubmitting = false,
  submitLabel = "发送",
  showContextControls = true,
  helperText = "Shift + Enter 换行，Enter 发送",
  modelOptions,
  selectedModelId,
  onModelChange,
  isLoadingModels = false,
  controlsDisabled = false,
  onCancel,
  mentionDocuments = [],
  isLoadingMentionDocuments = false,
}: ChatComposerProps) {
  const textAreaRef = useRef<HTMLTextAreaElement>(null);
  const resizeStateRef = useRef<ChatInputResizeState | null>(null);
  const [chatInput, setChatInput] = useState("");
  const [ragEnabled, setRagEnabled] = useState(true);
  const [modelId, setModelId] = useState("");
  const [chatInputHeight, setChatInputHeight] = useState(
    getChatInputDefaultHeight,
  );
  const [isResizingInput, setIsResizingInput] = useState(false);
  const [mentionTrigger, setMentionTrigger] = useState<MentionTrigger | null>(
    null,
  );
  const [mentionPickerOpen, setMentionPickerOpen] = useState(false);
  const [mentionSelectedIndex, setMentionSelectedIndex] = useState(0);
  const [mentionPage, setMentionPage] = useState(0);
  const [selectedMentions, setSelectedMentions] = useState<
    ChatMentionDocument[]
  >([]);
  const availableModels = modelOptions ?? [];
  const currentModelId = selectedModelId ?? modelId;
  const hasMessageContent = chatInput.trim().length > 0;
  const isBusy = disabled || isSubmitting;
  const isControlBusy = isBusy || controlsDisabled;
  const selectedModel =
    availableModels.find((model) => model.id === currentModelId) ??
    (currentModelId
      ? {
          id: currentModelId,
          label: currentModelId,
        }
      : null);
  const filteredMentionDocuments = useMemo(() => {
    const keyword = mentionTrigger?.query.trim().toLowerCase() ?? "";

    if (!keyword) {
      return mentionDocuments;
    }

    return mentionDocuments.filter((document) =>
      document.name.toLowerCase().includes(keyword),
    );
  }, [mentionDocuments, mentionTrigger?.query]);
  const mentionPageCount = Math.max(
    1,
    Math.ceil(filteredMentionDocuments.length / MENTION_PAGE_SIZE),
  );
  const visibleMentionDocuments = filteredMentionDocuments.slice(
    mentionPage * MENTION_PAGE_SIZE,
    mentionPage * MENTION_PAGE_SIZE + MENTION_PAGE_SIZE,
  );
  const chatInputMaxHeight = getChatInputMaxHeight();

  useEffect(() => {
    const handleWindowResize = () => {
      setChatInputHeight((currentHeight) =>
        clampChatInputHeight(currentHeight),
      );
    };

    window.addEventListener("resize", handleWindowResize);
    return () => window.removeEventListener("resize", handleWindowResize);
  }, []);

  useEffect(() => {
    if (!isResizingInput) {
      return;
    }

    const handlePointerMove = (event: globalThis.PointerEvent) => {
      const resizeState = resizeStateRef.current;

      if (!resizeState) {
        return;
      }

      event.preventDefault();
      setChatInputHeight(
        clampChatInputHeight(
          resizeState.startHeight + resizeState.startY - event.clientY,
        ),
      );
    };

    const stopResizing = (event: globalThis.PointerEvent) => {
      const resizeState = resizeStateRef.current;

      if (resizeState && event.pointerId !== resizeState.pointerId) {
        return;
      }

      resizeStateRef.current = null;
      setIsResizingInput(false);
    };

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", stopResizing);
    window.addEventListener("pointercancel", stopResizing);

    return () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", stopResizing);
      window.removeEventListener("pointercancel", stopResizing);
    };
  }, [isResizingInput]);

  const handleRagToggle = () => {
    setRagEnabled((currentValue) => {
      const nextValue = !currentValue;
      toast.success(nextValue ? "RAG 已启用" : "RAG 已关闭");
      return nextValue;
    });
  };

  const handleChatInputChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
    const nextValue = event.target.value;
    const cursorPosition = event.target.selectionStart ?? nextValue.length;
    const nextTrigger = getMentionTrigger(nextValue, cursorPosition);

    setChatInput(nextValue);
    setSelectedMentions((currentMentions) =>
      currentMentions.filter((mention) =>
        hasMentionToken(nextValue, mention.name),
      ),
    );
    setMentionTrigger(nextTrigger);
    setMentionPickerOpen(Boolean(nextTrigger));
    setMentionSelectedIndex(0);
    setMentionPage(0);
  };

  const insertMention = (document: ChatMentionDocument) => {
    const cursorPosition =
      textAreaRef.current?.selectionStart ?? chatInput.length;
    const trigger = mentionTrigger ?? getMentionTrigger(chatInput, cursorPosition);
    const mentionStartIndex = trigger?.start ?? -1;
    const mentionEndIndex = trigger?.end ?? cursorPosition;
    const insertion = `@${document.name} `;
    const nextInput =
      mentionStartIndex >= 0
        ? `${chatInput.slice(0, mentionStartIndex)}${insertion}${chatInput.slice(mentionEndIndex)}`
        : `${chatInput}${chatInput.endsWith(" ") || !chatInput ? "" : " "}${insertion}`;
    const nextCursorPosition =
      mentionStartIndex >= 0
        ? mentionStartIndex + insertion.length
        : nextInput.length;

    setChatInput(nextInput);
    setSelectedMentions((currentMentions) =>
      currentMentions.some((mention) => mention.id === document.id)
        ? currentMentions
        : [...currentMentions, document],
    );
    setMentionPickerOpen(false);
    setMentionTrigger(null);
    setMentionSelectedIndex(0);
    setMentionPage(0);

    window.requestAnimationFrame(() => {
      textAreaRef.current?.focus();
      textAreaRef.current?.setSelectionRange(
        nextCursorPosition,
        nextCursorPosition,
      );
    });
  };

  const removeMention = (documentId: number) => {
    const targetMention = selectedMentions.find(
      (mention) => mention.id === documentId,
    );

    setSelectedMentions((currentMentions) =>
      currentMentions.filter((mention) => mention.id !== documentId),
    );
    if (targetMention) {
      const escapedName = escapeRegExp(targetMention.name);
      const tokenPattern = new RegExp(`(^|\\s)@${escapedName}\\s?`, "u");
      setChatInput((currentInput) => currentInput.replace(tokenPattern, "$1"));
    }
    setMentionPickerOpen(false);
    setMentionTrigger(null);
  };

  const handleChatInputResizeStart = (
    event: PointerEvent<HTMLButtonElement>,
  ) => {
    if (isBusy) {
      return;
    }

    event.preventDefault();
    resizeStateRef.current = {
      pointerId: event.pointerId,
      startY: event.clientY,
      startHeight: chatInputHeight,
    };
    setIsResizingInput(true);
  };

  const handleSendMessage = async () => {
    if (!hasMessageContent || disabled || isSubmitting) {
      return;
    }

    const payload = {
      message: chatInput.trim(),
      modelId: selectedModel?.id ?? currentModelId,
      ragEnabled: showContextControls ? ragEnabled : true,
      mentionedDocumentIds: selectedMentions.map((mention) => mention.id),
    };

    try {
      await onSubmit(payload);
      setChatInput("");
      setSelectedMentions([]);
      setMentionPickerOpen(false);
      setMentionTrigger(null);
      setMentionSelectedIndex(0);
      setMentionPage(0);
    } catch {
      return;
    }
  };

  const handleChatKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (mentionPickerOpen) {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        setMentionSelectedIndex((currentIndex) =>
          visibleMentionDocuments.length === 0
            ? 0
            : Math.min(currentIndex + 1, visibleMentionDocuments.length - 1),
        );
        return;
      }

      if (event.key === "ArrowUp") {
        event.preventDefault();
        setMentionSelectedIndex((currentIndex) =>
          Math.max(currentIndex - 1, 0),
        );
        return;
      }

      if (event.key === "ArrowRight") {
        event.preventDefault();
        setMentionPage((currentPage) => {
          const nextPage = Math.min(currentPage + 1, mentionPageCount - 1);
          if (nextPage !== currentPage) {
            setMentionSelectedIndex(0);
          }
          return nextPage;
        });
        return;
      }

      if (event.key === "ArrowLeft") {
        event.preventDefault();
        setMentionPage((currentPage) => {
          const nextPage = Math.max(currentPage - 1, 0);
          if (nextPage !== currentPage) {
            setMentionSelectedIndex(0);
          }
          return nextPage;
        });
        return;
      }

      if (event.key === "Enter" || event.key === "Tab") {
        const selectedDocument = visibleMentionDocuments[mentionSelectedIndex];

        if (selectedDocument) {
          event.preventDefault();
          insertMention(selectedDocument);
          return;
        }
      }

      if (event.key === "Escape") {
        event.preventDefault();
        setMentionPickerOpen(false);
        setMentionTrigger(null);
        return;
      }
    }

    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      handleSendMessage();
    }
  };

  return (
    <TooltipProvider>
      <div
        className={cn(
          "mx-auto max-w-[800px] rounded-[8px] border border-slate-200 bg-white p-3 shadow-sm",
          className,
        )}
      >
        {selectedMentions.length > 0 && (
          <div className="mb-3 flex flex-wrap gap-2">
            {selectedMentions.map((mention) => (
              <span
                key={mention.id}
                className="inline-flex max-w-full items-center gap-2 rounded-[6px] border border-blue-100 bg-blue-50 px-2 py-1 text-xs text-blue-700"
                title={mention.name}
              >
                <FileText className="size-3.5 shrink-0" />
                <span className="max-w-[220px] truncate">{mention.name}</span>
                <button
                  type="button"
                  className="rounded text-blue-400 transition-colors hover:text-blue-700"
                  aria-label={`移除 ${mention.name}`}
                  onClick={() => removeMention(mention.id)}
                  disabled={isBusy}
                >
                  <X className="size-3.5" />
                </button>
              </span>
            ))}
          </div>
        )}

        <div className="flex min-w-0 items-start gap-2">
          <div className="relative min-w-0 flex-1 pt-2">
            <button
              type="button"
              aria-label="调整输入框高度"
              className={cn(
                "absolute top-1 left-1/2 z-10 flex h-3 w-24 -translate-x-1/2 cursor-ns-resize touch-none items-center justify-center rounded-full text-slate-300 transition-colors hover:text-slate-500 focus-visible:ring-2 focus-visible:ring-blue-200 focus-visible:outline-none",
                isResizingInput && "text-blue-500",
                isBusy && "cursor-not-allowed opacity-50",
              )}
              disabled={isBusy}
              onPointerDown={handleChatInputResizeStart}
            >
              <span className="h-1 w-12 rounded-full bg-current" />
            </button>
            <Textarea
              ref={textAreaRef}
              value={chatInput}
              maxLength={MAX_CHAT_MESSAGE_LENGTH}
              onChange={handleChatInputChange}
              onKeyDown={handleChatKeyDown}
              onFocus={() => {
                const cursorPosition =
                  textAreaRef.current?.selectionStart ?? chatInput.length;
                const nextTrigger = getMentionTrigger(chatInput, cursorPosition);
                if (nextTrigger) {
                  setMentionTrigger(nextTrigger);
                  setMentionPickerOpen(true);
                }
              }}
              className="w-full resize-none rounded-[6px] border-transparent px-2 py-2 text-sm leading-6 placeholder:text-slate-400 focus-visible:border-transparent focus-visible:ring-0"
              style={{
                height: `${chatInputHeight}px`,
                minHeight: `${CHAT_INPUT_MIN_HEIGHT}px`,
                maxHeight: `${chatInputMaxHeight}px`,
              }}
              placeholder={placeholder}
              aria-label="输入问题"
              disabled={isBusy}
            />
            {mentionPickerOpen && !isBusy && (
              <div className="absolute right-0 bottom-full left-0 z-20 mb-2 max-h-[50dvh] min-w-0 max-w-full rounded-[8px] border border-slate-200 bg-white p-2 shadow-lg sm:left-auto sm:w-[28rem]">
                <div className="px-2 py-1 text-xs text-slate-500">
                  {mentionTrigger?.query
                    ? `筛选：${mentionTrigger.query}`
                    : "输入文档名继续筛选"}
                </div>
                <div className="mt-2 max-h-48 overflow-auto">
                  {isLoadingMentionDocuments ? (
                    <div className="flex items-center gap-2 px-2 py-3 text-xs text-slate-500">
                      <Loader2 className="size-3.5 animate-spin" />
                      正在加载文档...
                    </div>
                  ) : visibleMentionDocuments.length > 0 ? (
                    visibleMentionDocuments.map((document, index) => (
                      <button
                        key={document.id}
                        type="button"
                        className={cn(
                          "flex min-h-10 w-full items-center gap-2 rounded-[6px] px-2 py-2 text-left text-xs text-slate-700 hover:bg-slate-50",
                          index === mentionSelectedIndex && "bg-blue-50 text-blue-700",
                        )}
                        title={document.name}
                        onMouseDown={(event) => {
                          event.preventDefault();
                          insertMention(document);
                        }}
                        >
                        <FileText className="size-3.5 shrink-0 text-slate-400" />
                        <span className="min-w-0 flex-1 break-words">
                          {highlightMatch(document.name, mentionTrigger?.query ?? "")}
                        </span>
                      </button>
                    ))
                  ) : (
                    <div className="px-2 py-3 text-xs text-slate-500">
                      当前知识库没有匹配文档
                    </div>
                  )}
                </div>
                {filteredMentionDocuments.length > MENTION_PAGE_SIZE ? (
                  <div className="mt-2 flex items-center justify-between gap-2 border-t border-slate-100 pt-2 text-xs text-slate-500">
                    <span>
                      {mentionPage + 1} / {mentionPageCount}
                    </span>
                    <div className="flex items-center gap-1">
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-xs"
                        aria-label="上一页"
                        disabled={mentionPage === 0}
                        onMouseDown={(event) => {
                          event.preventDefault();
                          setMentionPage((currentPage) =>
                            Math.max(currentPage - 1, 0),
                          );
                          setMentionSelectedIndex(0);
                        }}
                      >
                        <ChevronLeft />
                      </Button>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-xs"
                        aria-label="下一页"
                        disabled={mentionPage >= mentionPageCount - 1}
                        onMouseDown={(event) => {
                          event.preventDefault();
                          setMentionPage((currentPage) =>
                            Math.min(currentPage + 1, mentionPageCount - 1),
                          );
                          setMentionSelectedIndex(0);
                        }}
                      >
                        <ChevronRight />
                      </Button>
                    </div>
                  </div>
                ) : null}
              </div>
            )}
          </div>
        </div>

        <div className="mt-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div className="grid min-w-0 grid-cols-[auto_minmax(0,1fr)] items-center gap-2 sm:flex sm:flex-wrap">
            {showContextControls && (
              <>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-10 shrink-0 rounded-[6px] border-slate-200 px-3 text-xs font-medium tracking-normal normal-case xl:h-8"
                      aria-pressed={ragEnabled}
                      onClick={handleRagToggle}
                      disabled={isControlBusy}
                    >
                      <span
                        className={cn(
                          "size-2 rounded-full",
                          ragEnabled ? "bg-emerald-500" : "bg-slate-300",
                        )}
                      />
                      {ragEnabled ? "RAG 已启用" : "RAG 已关闭"}
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent sideOffset={6}>
                    开启时会检索知识库片段并展示引用；关闭时只按当前会话和模型回答，不生成引用来源。
                  </TooltipContent>
                </Tooltip>

                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-10 max-w-full min-w-0 flex-1 rounded-[6px] border-slate-200 px-3 text-xs font-medium tracking-normal normal-case xl:h-8 xl:flex-none xl:max-w-[15rem]"
                      disabled={
                        isControlBusy ||
                        isLoadingModels ||
                        availableModels.length === 0
                      }
                      title={selectedModel?.label || "未选择模型"}
                    >
                      {isLoadingModels ? (
                        <Loader2
                          data-icon="inline-start"
                          className="animate-spin"
                        />
                      ) : (
                        <Bot data-icon="inline-start" />
                      )}
                      <span className="min-w-0 truncate">
                        {selectedModel?.label || "使用默认模型"}
                      </span>
                      <ChevronDown data-icon="inline-end" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent
                    align="start"
                    className="w-48 rounded-[8px]"
                  >
                    <DropdownMenuGroup>
                      {availableModels.map((model) => (
                        <DropdownMenuItem
                          key={model.id}
                          title={model.label}
                          onSelect={() => {
                            setModelId(model.id);
                            onModelChange?.(model.id);
                          }}
                        >
                          <Check
                            className={cn(
                              "opacity-0",
                              model.id === selectedModel?.id && "opacity-100",
                            )}
                          />
                          <span className="min-w-0 truncate">
                            {model.label}
                          </span>
                        </DropdownMenuItem>
                      ))}
                    </DropdownMenuGroup>
                  </DropdownMenuContent>
                </DropdownMenu>
              </>
            )}
          </div>

          <div className="flex shrink-0 items-center justify-between gap-2 sm:justify-end">
            <span className="text-xs text-slate-500">
              {chatInput.length}/{MAX_CHAT_MESSAGE_LENGTH}
            </span>
            <Button
              type="button"
              size="sm"
              className="h-10 rounded-[6px] bg-blue-600 px-4 text-xs font-medium tracking-normal text-white normal-case hover:bg-blue-700 xl:h-8 xl:px-3"
              aria-label={isSubmitting ? "打断" : "发送"}
              disabled={!isSubmitting && (!hasMessageContent || disabled)}
              onClick={() => {
                if (isSubmitting) {
                  void onCancel?.();
                  return;
                }

                void handleSendMessage();
              }}
            >
              {isSubmitting ? (
                <Loader2 data-icon="inline-start" className="animate-spin" />
              ) : null}
              {submitLabel}
              {!isSubmitting && <Send data-icon="inline-end" />}
            </Button>
          </div>
        </div>
      </div>
      {helperText && (
        <div className="mx-auto mt-2 hidden max-w-[800px] px-1 text-xs text-slate-500 sm:block">
          {helperText}
        </div>
      )}
    </TooltipProvider>
  );
}
