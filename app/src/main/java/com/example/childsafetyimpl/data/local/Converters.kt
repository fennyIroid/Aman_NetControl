package com.example.childsafetyimpl.data.local

import androidx.room.TypeConverter
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.CloneKind
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.RuleAction
import com.example.childsafetyimpl.domain.model.RuleSource
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.WebCategory

/**
 * Enums are stored by name rather than ordinal on purpose: the catalog and the event taxonomy will
 * both gain entries during R&D, and an ordinal-based column would silently reinterpret old rows.
 */
class SafetyConverters {
    @TypeConverter fun toEventType(value: String) = SafetyEventType.valueOf(value)
    @TypeConverter fun fromEventType(value: SafetyEventType) = value.name

    @TypeConverter fun toSeverity(value: String) = EventSeverity.valueOf(value)
    @TypeConverter fun fromSeverity(value: EventSeverity) = value.name

    @TypeConverter fun toAction(value: String) = EventAction.valueOf(value)
    @TypeConverter fun fromAction(value: EventAction) = value.name

    @TypeConverter fun toAppCategory(value: String) = AppCategory.valueOf(value)
    @TypeConverter fun fromAppCategory(value: AppCategory) = value.name

    @TypeConverter fun toWebCategory(value: String) = WebCategory.valueOf(value)
    @TypeConverter fun fromWebCategory(value: WebCategory) = value.name

    @TypeConverter fun toRuleAction(value: String) = RuleAction.valueOf(value)
    @TypeConverter fun fromRuleAction(value: RuleAction) = value.name

    @TypeConverter fun toRuleSource(value: String) = RuleSource.valueOf(value)
    @TypeConverter fun fromRuleSource(value: RuleSource) = value.name

    @TypeConverter fun toSurface(value: String) = RestrictedSurface.valueOf(value)
    @TypeConverter fun fromSurface(value: RestrictedSurface) = value.name

    @TypeConverter fun toCloneKind(value: String) = CloneKind.valueOf(value)
    @TypeConverter fun fromCloneKind(value: CloneKind) = value.name
}
