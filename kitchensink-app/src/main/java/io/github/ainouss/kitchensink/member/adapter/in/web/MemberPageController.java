package io.github.ainouss.kitchensink.member.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.ainouss.kitchensink.member.application.FindMembers;
import io.github.ainouss.kitchensink.member.application.RegisterMember;
import io.github.ainouss.kitchensink.member.domain.EmailTakenException;
import io.github.ainouss.kitchensink.member.domain.NewMember;

/**
 * The registration page, rebuilt from the legacy JSF page: same form, messages and languages (R14, R16).
 * Served at {@code /} and at the legacy addresses under {@code /kitchensink}; the form posts back to the same address.
 *
 * <p>Two changes from the legacy: a redirect after registering, so a refresh does not register twice; and a paged
 * table, because the legacy page loads every member in memory at once (R17).
 */
@Controller
@RequestMapping({"/", "/index.jsf", "/index.html", "/kitchensink", "/kitchensink/", "/kitchensink/index.jsf",
        "/kitchensink/index.html"})
class MemberPageController {

    static final int PAGE_SIZE = 25;

    /** The furthest page the database can skip to (its skip is an int). */
    private static final int LAST_REACHABLE_PAGE = Integer.MAX_VALUE / PAGE_SIZE - 1;

    private static final Logger log = LoggerFactory.getLogger(MemberPageController.class);

    private final FindMembers find;
    private final RegisterMember register;
    private final Validator validator;

    MemberPageController(FindMembers find, RegisterMember register, Validator validator) {
        this.find = find;
        this.register = register;
        this.validator = validator;
    }

    @GetMapping
    String page(@ModelAttribute("newMember") MemberForm form, @RequestParam(defaultValue = "0") int page,
                HttpServletRequest request, Model model) {
        return render(model, page, request);
    }

    @PostMapping
    String register(@ModelAttribute("newMember") MemberForm form, BindingResult result,
                    @RequestParam(defaultValue = "0") int page, HttpServletRequest request, Model model,
                    RedirectAttributes redirect) {
        NewMember registration = form.toNewMember();
        // Spring's validator gives the messages in the browser's language.
        validator.validate(registration).forEach(violation ->
                result.rejectValue(violation.getPropertyPath().toString(), "invalid", violation.getMessage()));
        if (result.hasErrors()) {
            return render(model, page, request);
        }
        try {
            register.register(registration);
        } catch (EmailTakenException e) {
            result.rejectValue("email", "emailTaken");
            model.addAttribute("failure", "emailTaken");
            return render(model, page, request);
        } catch (RuntimeException e) {
            // Show the root cause's message, as the legacy did (R14).
            log.warn("Registration failed unexpectedly", e);
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            model.addAttribute("failureText", String.valueOf(root.getLocalizedMessage()));
            return render(model, page, request);
        }
        redirect.addFlashAttribute("registered", true);
        return "redirect:" + base(request) + "/";
    }

    private String render(Model model, int page, HttpServletRequest request) {
        long total = find.count();
        long pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        // A page number out of range shows the nearest page instead.
        int shown = (int) Math.min(Math.max(page, 0), Math.min(pages - 1, LAST_REACHABLE_PAGE));
        FindMembers.MemberPage members = find.page(shown, PAGE_SIZE);
        model.addAttribute("members", members.content());
        model.addAttribute("page", shown);
        model.addAttribute("pages", pages);
        model.addAttribute("total", total);
        model.addAttribute("base", base(request));
        return "index";
    }

    /** {@code /kitchensink} when the page was opened at a legacy address, so links stay under it. */
    private static String base(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/kitchensink") ? "/kitchensink" : "";
    }

    /** The form's fields. An empty field is an empty string, not null, as in the legacy. */
    static class MemberForm {
        private String name = "";
        private String email = "";
        private String phoneNumber = "";

        NewMember toNewMember() {
            return new NewMember(name, email, phoneNumber);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }
    }
}
